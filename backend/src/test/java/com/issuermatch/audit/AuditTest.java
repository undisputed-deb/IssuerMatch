package com.issuermatch.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class AuditTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    final UUID alice = UUID.randomUUID();

    ResultActions call(MockHttpServletRequestBuilder req, RequestPostProcessor user, String json) throws Exception {
        return mvc.perform(req.with(user).contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    List<Map<String, Object>> auditFor(String actor) {
        return db.queryForList("SELECT action, resource, ip, details::text AS details FROM audit_log WHERE actor = ? ORDER BY at, id", actor);
    }

    String idOf(String json) {
        return json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void issuerLifecycleIsAuditedWithActorResourceAndIp() throws Exception {
        String res = call(post("/issuers"), Auth.issuer(alice),
                "{\"name\":\"Audit " + UUID.randomUUID() + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = idOf(res);
        call(put("/issuers/" + id), Auth.issuer(alice), "{\"name\":\"Renamed " + UUID.randomUUID() + "\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}")
                .andExpect(status().isOk());
        call(delete("/issuers/" + id), Auth.issuer(alice), null).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = auditFor(alice.toString());
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("ISSUER_CREATED", "ISSUER_UPDATED", "ISSUER_DELETED");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("resource")).isEqualTo("issuer:" + id);
            assertThat(r.get("ip")).isEqualTo("127.0.0.1");
        });
    }

    @Test
    void everyAuditRowAlsoGoesToTheAuditEventsTopicViaTheOutbox() throws Exception {
        call(post("/issuers"), Auth.issuer(alice),
                "{\"name\":\"Audit " + UUID.randomUUID() + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}").andExpect(status().isCreated());
        String payload = db.queryForObject("SELECT payload FROM outbox WHERE topic = 'audit.events' AND event_key = ?",
                String.class, alice.toString());
        assertThat(payload).contains("ISSUER_CREATED").contains(alice.toString()).contains("\"AuditEvent\"");
    }

    @Test
    void aFailedRequestLeavesNoAuditRow() throws Exception {
        call(put("/issuers/" + UUID.randomUUID()), Auth.issuer(alice), "{\"name\":\"x\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}")
                .andExpect(status().isNotFound());
        assertThat(auditFor(alice.toString())).isEmpty(); // nothing happened, so nothing to record
    }

    @Test
    void fundingNeedSubmissionIsAuditedWithoutItsFreeText() throws Exception {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "AuditN " + UUID.randomUUID(), alice);
        call(post("/funding-needs"), Auth.issuer(alice), "{\"issuerId\":\"" + issuer + "\",\"title\":\"Secret title\","
                + "\"description\":\"Confidential plan SECRET-TEXT\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\","
                + "\"amount\":5000,\"instrument\":\"EQUITY\",\"riskLevel\":\"LOW\"}").andExpect(status().isAccepted());

        Map<String, Object> row = auditFor(alice.toString()).get(0);
        assertThat(row.get("action")).isEqualTo("FUNDING_NEED_SUBMITTED");
        assertThat((String) row.get("details")).contains("5000").doesNotContain("SECRET-TEXT").doesNotContain("Secret title");
    }

    @Test
    void reviewDecisionsAreAuditedWithTheReviewer() throws Exception {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'PENDING_REVIEW') RETURNING id", UUID.class,
                db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                        + " VALUES (?,'t','d','FINTECH','EUROPE',1,'EQUITY','LOW',1) RETURNING id", UUID.class,
                        db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                                UUID.class, "AuditR " + UUID.randomUUID(), alice)), UUID.randomUUID());
        UUID carol = UUID.randomUUID();
        call(post("/match-runs/" + run + "/approve"), Auth.as(carol, "ANALYST"), null).andExpect(status().isOk());

        assertThat(auditFor(carol.toString())).singleElement().satisfies(r -> {
            assertThat(r.get("action")).isEqualTo("MATCH_APPROVED");
            assertThat(r.get("resource")).isEqualTo("match-run:" + run);
        });
        // a second, rejected attempt (409) must not be recorded as another approval
        call(post("/match-runs/" + run + "/approve"), Auth.as(carol, "ANALYST"), null).andExpect(status().isConflict());
        assertThat(auditFor(carol.toString())).hasSize(1);
    }
}
