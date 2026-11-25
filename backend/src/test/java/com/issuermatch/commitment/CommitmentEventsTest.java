package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real Postgres and Kafka: commitment decisions become events, and the ledger records each exactly once. */
@IntegrationTest
class CommitmentEventsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaContainer kafkaContainer;
    @Autowired JsonMapper json;

    UUID issuerId;
    UUID needId;
    UUID investorId;
    final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String k : keys) db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", k);
        if (needId != null) {
            db.update("DELETE FROM commitment_events WHERE need_id = ?", needId);
            db.update("DELETE FROM commitments WHERE need_id = ?", needId);
            db.update("DELETE FROM issuers WHERE id = ?", issuerId);
            db.update("DELETE FROM investors WHERE id = ?", investorId);
        }
    }

    void givenApprovedMatch() {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Ev " + UUID.randomUUID(), UUID.randomUUID());
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',10000000,'EQUITY','MEDIUM',10000000) RETURNING id",
                UUID.class, issuerId);
        investorId = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Ev Inv " + UUID.randomUUID());
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',100000,1000000,'MEDIUM','{EQUITY}')", investorId);
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id",
                UUID.class, needId, UUID.randomUUID());
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,100,'ok')", run, investorId);
    }

    /** returns the commitment id */
    String commit(long amount, String key) throws Exception {
        keys.add(key);
        return mvc.perform(post("/commitments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\",\"amount\":" + amount + "}"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
    }

    String commit(long amount) throws Exception {
        return commit(amount, "ev-" + UUID.randomUUID());
    }

    List<String> ledger(String commitmentId) {
        return db.queryForList("SELECT type FROM commitment_events WHERE commitment_id = ?::uuid ORDER BY type", String.class, commitmentId);
    }

    void awaitLedger(String commitmentId, String... types) {
        await().atMost(Duration.ofSeconds(30)).until(() -> ledger(commitmentId).equals(List.of(types)));
    }

    @Test
    void anAcceptedCommitmentIsRecordedAsRequestedAndAccepted() throws Exception {
        givenApprovedMatch();
        String id = commit(500_000);
        awaitLedger(id, "ACCEPTED", "REQUESTED");
        assertThat(db.queryForObject("SELECT amount::text FROM commitment_events WHERE commitment_id = ?::uuid AND type='ACCEPTED'",
                String.class, id)).isEqualTo("500000.00");
    }

    @Test
    void aBlockedCommitmentIsRecordedAsRequestedAndRejectedWithTheReason() throws Exception {
        givenApprovedMatch();
        String id = commit(2_000_000);
        awaitLedger(id, "REJECTED", "REQUESTED");
        assertThat(db.queryForObject("SELECT reason FROM commitment_events WHERE commitment_id = ?::uuid AND type='REJECTED'",
                String.class, id)).contains("outside the nearest mandate range");
    }

    @Test
    void aFlaggedCommitmentOnlySaysRequestedUntilSomeoneDecides() throws Exception {
        givenApprovedMatch();
        String id = commit(1_200_000);
        awaitLedger(id, "REQUESTED");
        mvc.perform(post("/commitments/" + id + "/approve").with(Auth.analyst()));
        awaitLedger(id, "ACCEPTED", "REQUESTED");
    }

    @Test
    void rejectingAFlaggedCommitmentIsRecordedAsRejected() throws Exception {
        givenApprovedMatch();
        String id = commit(1_200_000);
        mvc.perform(post("/commitments/" + id + "/reject").with(Auth.analyst()));
        awaitLedger(id, "REJECTED", "REQUESTED");
    }

    @Test
    void aRetriedRequestPublishesNothingNew() throws Exception {
        givenApprovedMatch();
        String key = "ev-" + UUID.randomUUID();
        String id = commit(500_000, key);
        commit(500_000, key); // replay
        awaitLedger(id, "ACCEPTED", "REQUESTED");
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic LIKE 'commitment.%' AND payload LIKE ?",
                Integer.class, "%" + id + "%")).isEqualTo(2);
    }

    String event(UUID eventId, String type, UUID commitmentId, Object amount) {
        return json.writeValueAsString(new EventEnvelope(eventId, type, Instant.now(), "test", Map.of(
                "commitmentId", commitmentId.toString(), "needId", needId.toString(),
                "investorId", investorId.toString(), "amount", amount)));
    }

    @Test
    void theSameEventDeliveredTwiceIsCountedOnce() throws Exception {
        givenApprovedMatch();
        UUID eventId = UUID.randomUUID();
        UUID commitment = UUID.randomUUID();
        String message = event(eventId, "CommitmentAccepted", commitment, "750000");
        kafka.send("commitment.accepted", needId.toString(), message).get();
        kafka.send("commitment.accepted", needId.toString(), message).get(); // redelivery
        UUID sentinel = UUID.randomUUID();
        kafka.send("commitment.accepted", needId.toString(), event(UUID.randomUUID(), "CommitmentAccepted", sentinel, "1")).get();

        await().atMost(Duration.ofSeconds(30)).until(() -> ledger(sentinel.toString()).size() == 1);
        assertThat(ledger(commitment.toString())).containsExactly("ACCEPTED");
        assertThat(db.queryForObject("SELECT sum(amount)::text FROM commitment_events WHERE commitment_id = ?", String.class, commitment))
                .isEqualTo("750000.00"); // the replay did not change totals
    }

    @Test
    void invalidEventsGoToTheDeadLetterTopicAndNeverReachTheLedger() throws Exception {
        givenApprovedMatch();
        UUID commitment = UUID.randomUUID();
        String negative = event(UUID.randomUUID(), "CommitmentAccepted", commitment, "-5");
        String wrongType = event(UUID.randomUUID(), "SomethingElse", commitment, "5");
        String notANumber = event(UUID.randomUUID(), "CommitmentAccepted", commitment, "lots");
        for (String bad : new String[] {negative, wrongType, notANumber}) {
            kafka.send("commitment.accepted", needId.toString(), bad).get();
            assertThat(awaitDeadLetter("commitment.accepted.DLT", bad)).isNotNull();
        }
        assertThat(ledger(commitment.toString())).isEmpty();
    }

    ConsumerRecord<String, String> awaitDeadLetter(String topic, String value) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (value.equals(r.value())) return r;
                }
            }
        }
        return null;
    }
}
