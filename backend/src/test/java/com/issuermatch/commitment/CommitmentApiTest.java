package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Committed rows (parallel requests run on other threads), cleaned up afterwards. */
@IntegrationTest
class CommitmentApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    UUID issuerId;
    UUID needId;
    UUID investorId;
    final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String k : keys) db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", k);
        if (issuerId != null) {
            db.update("DELETE FROM commitments WHERE need_id = ?", needId);
            db.update("DELETE FROM issuers WHERE id = ?", issuerId); // cascades needs, runs, results
        }
        if (investorId != null) db.update("DELETE FROM investors WHERE id = ?", investorId);
    }

    String key() {
        String k = "test-" + UUID.randomUUID();
        keys.add(k);
        return k;
    }

    /** A need, an investor and an APPROVED match between them. */
    void givenApprovedMatch() {
        givenMatch("APPROVED");
    }

    void givenMatch(String runStatus) {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Commit " + UUID.randomUUID(), UUID.randomUUID());
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',10000000,'EQUITY','MEDIUM',10000000) RETURNING id",
                UUID.class, issuerId);
        investorId = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Commit Inv " + UUID.randomUUID());
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,?) RETURNING id",
                UUID.class, needId, UUID.randomUUID(), runStatus);
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,100,'ok')", run, investorId);
    }

    String body(long amount) {
        return "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\",\"amount\":" + amount + "}";
    }

    org.springframework.test.web.servlet.ResultActions send(String key, String body) throws Exception {
        var req = post("/commitments").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    int commitmentCount() {
        return db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ?", Integer.class, needId);
    }

    @Test
    void createsACommitmentForAnApprovedMatch() throws Exception {
        givenApprovedMatch();
        send(key(), body(500_000)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.amount").value(500000))
                .andExpect(jsonPath("$.investorId").value(investorId.toString()));
        assertThat(commitmentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyAndSameBodyReplaysTheSavedResponseWithoutAWriteBack() throws Exception {
        givenApprovedMatch();
        String k = key();
        String first = send(k, body(500_000)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        MvcResult second = send(k, body(500_000)).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(commitmentCount()).isEqualTo(1);
    }

    @Test
    void whitespaceDifferencesInTheBodyDoNotCountAsADifferentRequest() throws Exception {
        givenApprovedMatch();
        String k = key();
        send(k, body(500_000)).andExpect(status().isCreated());
        send(k, "{ \"amount\" : 500000.00 , \"investorId\":\"" + investorId + "\", \"needId\":\"" + needId + "\" }")
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(commitmentCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBodyIs422() throws Exception {
        givenApprovedMatch();
        String k = key();
        send(k, body(500_000)).andExpect(status().isCreated());
        send(k, body(900_000)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("UNPROCESSABLE_ENTITY"));
        assertThat(commitmentCount()).isEqualTo(1);
    }

    @Test
    void differentKeysCreateSeparateCommitments() throws Exception {
        givenApprovedMatch();
        send(key(), body(500_000)).andExpect(status().isCreated());
        send(key(), body(500_000)).andExpect(status().isCreated());
        assertThat(commitmentCount()).isEqualTo(2);
    }

    @Test
    void fiftyParallelIdenticalRequestsCreateExactlyOneCommitment() throws Exception {
        givenApprovedMatch();
        String k = key();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return send(k, body(500_000)).andReturn();
            }));
        }
        start.countDown();

        Set<String> ids = new HashSet<>();
        for (Future<MvcResult> f : results) {
            MvcResult r = f.get();
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            ids.add(r.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        }
        pool.shutdown();

        assertThat(commitmentCount()).isEqualTo(1);   // one row in the database
        assertThat(ids).hasSize(1);                   // and every caller got that same commitment back
    }

    @Test
    void keyIsRequiredAndMustBeSane() throws Exception {
        givenApprovedMatch();
        send(null, body(1)).andExpect(status().isBadRequest());
        send("", body(1)).andExpect(status().isBadRequest());
        send("x".repeat(129), body(1)).andExpect(status().isBadRequest());
        send("bad key with spaces", body(1)).andExpect(status().isBadRequest());
        assertThat(commitmentCount()).isZero();
    }

    @Test
    void onlyApprovedMatchesCanCommit() throws Exception {
        givenMatch("PENDING_REVIEW");
        send(key(), body(1000)).andExpect(status().isConflict());
        assertThat(commitmentCount()).isZero();
    }

    @Test
    void aFailedRequestDoesNotBurnTheKey() throws Exception {
        givenMatch("PENDING_REVIEW");
        String k = key();
        send(k, body(1000)).andExpect(status().isConflict());           // not approved yet: rejected, nothing stored
        db.update("UPDATE match_runs SET status='APPROVED' WHERE need_id = ?", needId);
        send(k, body(1000)).andExpect(status().isCreated());            // same key now works
        assertThat(commitmentCount()).isEqualTo(1);
    }

    @Test
    void validationAndUnknownIds() throws Exception {
        givenApprovedMatch();
        send(key(), body(0)).andExpect(status().isBadRequest());
        send(key(), body(-5)).andExpect(status().isBadRequest());
        send(key(), "{\"needId\":\"" + UUID.randomUUID() + "\",\"investorId\":\"" + investorId + "\",\"amount\":10}")
                .andExpect(status().isNotFound());
        send(key(), "{\"needId\":\"" + needId + "\",\"investorId\":\"" + UUID.randomUUID() + "\",\"amount\":10}")
                .andExpect(status().isNotFound());
        send(key(), "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\",\"amount\":10,\"status\":\"ACCEPTED\"}")
                .andExpect(status().isBadRequest()); // unknown field
    }

    @Test
    void idempotencyKeysBelongToTheCallerAndTheCreatorIsRecorded() throws Exception {
        givenApprovedMatch();
        String k = key();
        String mine = send(k, body(500_000)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        // Another user reusing my key gets their OWN commitment: no replay of my response, nothing leaks.
        MvcResult theirs = mvc.perform(post("/commitments").contentType(MediaType.APPLICATION_JSON).content(body(500_000))
                        .header("Idempotency-Key", k).with(Auth.analyst()))
                .andExpect(status().isCreated()).andReturn();
        assertThat(theirs.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(theirs.getResponse().getContentAsString()).isNotEqualTo(mine);
        assertThat(commitmentCount()).isEqualTo(2);

        // My own retry still replays mine.
        send(k, body(500_000)).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(commitmentCount()).isEqualTo(2);

        // created_by comes from the token
        assertThat(db.queryForList("SELECT created_by::text FROM commitments WHERE need_id = ? ORDER BY created_by", String.class, needId))
                .containsExactly("00000000-0000-0000-0000-0000000000a1", "00000000-0000-0000-0000-0000000000ad");
    }
}
