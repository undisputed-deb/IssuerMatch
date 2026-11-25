package com.issuermatch.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** A poison message lands in the dead-letter table with its reason, and an admin can replay it. */
@IntegrationTest
class DeadLetterTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JsonMapper json;

    final List<UUID> issuers = new ArrayList<>();
    final List<String> markers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID i : issuers) db.update("DELETE FROM issuers WHERE id = ?", i);
        for (String m : markers) db.update("DELETE FROM dead_letters WHERE payload LIKE ?", "%" + m + "%");
    }

    Map<String, Object> awaitDeadLetter(String marker) {
        markers.add(marker);
        await().atMost(Duration.ofSeconds(40)).until(() -> db.queryForObject(
                "SELECT count(*) FROM dead_letters WHERE payload LIKE ?", Integer.class, "%" + marker + "%") >= 1);
        return db.queryForMap("SELECT id, source_topic, message_key, payload, reason, replayed_at FROM dead_letters"
                + " WHERE payload LIKE ? ORDER BY received_at LIMIT 1", "%" + marker + "%");
    }

    String envelope(String type, Map<String, Object> payload) {
        return json.writeValueAsString(new EventEnvelope(UUID.randomUUID(), type, Instant.now(), "test", payload));
    }

    @Test
    void aPoisonMessageIsStoredWithItsOriginalPayloadAndTheReason() throws Exception {
        String marker = UUID.randomUUID().toString();
        String bad = envelope("CommitmentAccepted", Map.of("commitmentId", marker, "needId", UUID.randomUUID().toString(),
                "investorId", UUID.randomUUID().toString(), "amount", "-5"));
        kafka.send("commitment.accepted", "key-1", bad).get();

        Map<String, Object> row = awaitDeadLetter(marker);

        assertThat(row.get("source_topic")).isEqualTo("commitment.accepted");
        assertThat(row.get("message_key")).isEqualTo("key-1");
        assertThat(row.get("payload")).isEqualTo(bad);                       // exactly as sent
        assertThat((String) row.get("reason")).contains("invalid amount");   // a person can act on this
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    void afterTheCauseIsFixedReplayProcessesTheMessage() throws Exception {
        UUID needId = UUID.randomUUID(); // does not exist yet: the consumer fails, retries, then dead-letters it
        String message = envelope("FundingNeedSubmitted", Map.of("needId", needId.toString(), "issuerId", UUID.randomUUID().toString()));
        kafka.send("funding-need.submitted", needId.toString(), message).get();
        Map<String, Object> row = awaitDeadLetter(needId.toString());
        assertThat(db.queryForObject("SELECT count(*) FROM match_runs WHERE need_id = ?", Integer.class, needId)).isZero();

        // fix the cause
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "DL " + UUID.randomUUID(), UUID.randomUUID());
        issuers.add(issuer);
        db.update("INSERT INTO funding_needs(id,issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                + " VALUES (?,?,'t','d','FINTECH','EUROPE',1000,'EQUITY','LOW',1000)", needId, issuer);

        UUID carol = UUID.randomUUID();
        mvc.perform(post("/admin/dead-letters/" + row.get("id") + "/replay").with(Auth.as(carol, "ADMIN")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.replayedBy").value(carol.toString()));

        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM match_runs WHERE need_id = ?", Integer.class, needId) == 1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEAD_LETTER_REPLAYED' AND actor = ?",
                Integer.class, carol.toString())).isEqualTo(1);

        // once only: a second replay is refused
        mvc.perform(post("/admin/dead-letters/" + row.get("id") + "/replay").with(Auth.admin())).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM match_runs WHERE need_id = ?", Integer.class, needId)).isEqualTo(1);
    }

    UUID insertDeadLetter(String topic, String marker) {
        markers.add(marker);
        return db.queryForObject("INSERT INTO dead_letters(source_topic,message_key,payload,reason,dlt_topic,dlt_partition,dlt_offset)"
                        + " VALUES (?,?,?,'test',?,0,?) RETURNING id", UUID.class, topic, "k", "{\"m\":\"" + marker + "\"}",
                topic + ".DLT", Math.abs(UUID.randomUUID().getLeastSignificantBits()));
    }

    @Test
    void adminsCanListFilterAndOpenDeadLetters() throws Exception {
        UUID id = insertDeadLetter("commitment.rejected", UUID.randomUUID().toString());
        mvc.perform(get("/admin/dead-letters?topic=commitment.rejected&status=PENDING").with(Auth.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.items[0].sourceTopic").value("commitment.rejected"));
        mvc.perform(get("/admin/dead-letters/" + id).with(Auth.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payload").isNotEmpty());
        mvc.perform(get("/admin/dead-letters?status=NONSENSE").with(Auth.admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/dead-letters/" + UUID.randomUUID()).with(Auth.admin())).andExpect(status().isNotFound());
        mvc.perform(post("/admin/dead-letters/" + UUID.randomUUID() + "/replay").with(Auth.admin())).andExpect(status().isNotFound());
    }

    @Test
    void replayOnlySendsToTopicsThisApplicationConsumes() throws Exception {
        UUID id = insertDeadLetter("someone.elses.topic", UUID.randomUUID().toString());
        mvc.perform(post("/admin/dead-letters/" + id + "/replay").with(Auth.admin())).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT replayed_at FROM dead_letters WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic = 'someone.elses.topic'", Integer.class)).isZero();
    }

    @Test
    void onlyAdminsMaySeeOrReplayDeadLetters() throws Exception {
        UUID id = insertDeadLetter("commitment.accepted", UUID.randomUUID().toString());
        for (RequestPostProcessor user : new RequestPostProcessor[] {Auth.analyst(), Auth.issuer(UUID.randomUUID()), Auth.noRoles()}) {
            mvc.perform(get("/admin/dead-letters").with(user)).andExpect(status().isForbidden());
            mvc.perform(get("/admin/dead-letters/" + id).with(user)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/dead-letters/" + id + "/replay").with(user)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/dead-letters").with(Auth.anonymous())).andExpect(status().isUnauthorized());
    }
}
