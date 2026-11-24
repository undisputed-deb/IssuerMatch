package com.issuermatch.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real Postgres + real Kafka. Not @Transactional: the consumer runs on another thread and must see committed rows. */
@IntegrationTest
class EventPipelineTest {

    @Autowired JdbcTemplate db;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaContainer kafkaContainer;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    UUID issuer() {
        return db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,?,?,?) RETURNING id",
                UUID.class, "Pipe " + UUID.randomUUID(), "FINTECH", "EUROPE", UUID.randomUUID());
    }

    UUID need(UUID issuerId) {
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1000,'EQUITY','LOW',1000) RETURNING id",
                UUID.class, issuerId);
    }

    String envelope(UUID eventId, UUID needId) {
        return json.writeValueAsString(new EventEnvelope(eventId, "FundingNeedSubmitted", java.time.Instant.now(), "test",
                Map.of("needId", needId.toString(), "issuerId", UUID.randomUUID().toString())));
    }

    int runs(UUID needId) {
        return db.queryForObject("SELECT count(*) FROM match_runs WHERE need_id = ?", Integer.class, needId);
    }

    int requestedEvents(UUID needId) {
        return db.queryForObject("SELECT count(*) FROM outbox WHERE topic = 'match.requested' AND event_key = ?",
                Integer.class, needId.toString());
    }

    ConsumerRecord<String, String> awaitDeadLetter(String containing) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("funding-need.submitted.DLT"));
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (r.value() != null && r.value().contains(containing)) return r;
                }
            }
        }
        throw new AssertionError("no dead letter containing " + containing);
    }

    @Test
    void duplicateEventDoesNotCreateASecondRun() throws Exception {
        UUID needA = need(issuer());
        UUID needSentinel = need(issuer());
        UUID eventId = UUID.randomUUID();

        kafka.send("funding-need.submitted", needA.toString(), envelope(eventId, needA)).get();
        kafka.send("funding-need.submitted", needA.toString(), envelope(eventId, needA)).get(); // same eventId again
        kafka.send("funding-need.submitted", needSentinel.toString(), envelope(UUID.randomUUID(), needSentinel)).get();

        // One partition = in-order. When the sentinel is done, the duplicate has already been handled (and skipped).
        await().atMost(Duration.ofSeconds(30)).until(() -> runs(needSentinel) == 1);

        assertThat(runs(needA)).isEqualTo(1);
        assertThat(requestedEvents(needA)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void submitEndpointFlowsThroughOutboxAndKafkaToAMatchRun() throws Exception {
        UUID issuerId = issuer();
        String res = mvc.perform(post("/funding-needs").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"issuerId\":\"" + issuerId + "\",\"title\":\"A\",\"description\":\"d\",\"sector\":\"FINTECH\","
                                + "\"region\":\"EUROPE\",\"amount\":1000000,\"instrument\":\"EQUITY\",\"riskLevel\":\"LOW\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID needId = UUID.fromString(res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));

        await().atMost(Duration.ofSeconds(30)).until(() -> runs(needId) == 1);
        assertThat(db.queryForObject("SELECT status FROM funding_needs WHERE id = ?", String.class, needId))
                .isEqualTo("MATCHING");
        // both events (submitted, match.requested) were published by the outbox poller
        await().atMost(Duration.ofSeconds(10)).until(() -> db.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_key = ? AND published_at IS NOT NULL"
                        + " AND topic IN ('funding-need.submitted', 'match.requested')", Integer.class,
                needId.toString()) == 2);
    }

    @Test
    void poisonMessageGoesStraightToTheDeadLetterTopic() throws Exception {
        String poison = "not json at all " + UUID.randomUUID();
        kafka.send("funding-need.submitted", "poison", poison).get();

        ConsumerRecord<String, String> dead = awaitDeadLetter(poison);

        assertThat(dead.value()).isEqualTo(poison); // original payload preserved for replay
        assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull(); // reason preserved
    }

    @Test
    void failingEventIsRetriedThenDeadLettered() throws Exception {
        UUID missingNeed = UUID.randomUUID(); // no such funding need: the insert violates the foreign key
        UUID eventId = UUID.randomUUID();
        kafka.send("funding-need.submitted", missingNeed.toString(), envelope(eventId, missingNeed)).get();

        awaitDeadLetter(eventId.toString());

        assertThat(runs(missingNeed)).isZero();
        // the failed transaction rolled back, so a later replay is not mistaken for a duplicate
        assertThat(db.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }
}
