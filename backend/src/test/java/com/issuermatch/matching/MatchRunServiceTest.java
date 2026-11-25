package com.issuermatch.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.matching.agent.MatchAgent;
import com.issuermatch.matching.agent.ModelOutput;
import com.issuermatch.matching.agent.RunToolbox;
import com.issuermatch.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class MatchRunServiceTest {

    @Autowired MatchRunService service;
    @Autowired JdbcTemplate db;

    UUID need;
    UUID run;

    void givenNeedAndRun() {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Svc " + UUID.randomUUID(), UUID.randomUUID());
        need = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1000000,'EQUITY','MEDIUM',1000000) RETURNING id",
                UUID.class, issuer);
        run = db.queryForObject("INSERT INTO match_runs(need_id,event_id) VALUES (?,?) RETURNING id", UUID.class, need, UUID.randomUUID());
    }

    /** sector,region,min,max,risk */
    UUID investor(String mandate) {
        UUID inv = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "I " + UUID.randomUUID());
        String[] p = mandate.split(",");
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,?,?,?::numeric,?::numeric,?,'{EQUITY}')", inv, p[0], p[1], p[2], p[3], p[4]);
        return inv;
    }

    ModelOutput.Entry entry(UUID inv, String text) {
        return new ModelOutput.Entry(inv, text, List.of("scoreFit"));
    }

    @Test
    void claimSucceedsOnceAndRejectsAWrongNeed() {
        givenNeedAndRun();
        assertThat(service.claim(run, UUID.randomUUID())).isFalse(); // forged need id
        assertThat(service.claim(run, need)).isTrue();
        assertThat(service.claim(run, need)).isFalse();              // duplicate delivery
    }

    @Test
    void scoresAndRanksComeFromJavaNotFromTheModel() {
        givenNeedAndRun();
        UUID weak = investor("ENERGY,LATAM,5000000,9000000,HIGH");
        UUID strong = investor("FINTECH,EUROPE,500000,2000000,MEDIUM");
        // The model lists the weak investor first and brags about a 99 score. Neither may matter.
        var entries = List.of(entry(weak, "Score 99, best ever!"), entry(strong, "Solid fit."));
        var calls = List.of(new RunToolbox.Call("scoreFit", "{\"investorId\":\"" + strong + "\"}", "{}", false, 3));

        service.claim(run, need);
        service.complete(run, need, new MatchAgent.Result(entries, new MatchAgent.Run("{}", 321, 45, 1500, calls)));

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT investor_id, rank, score, explanation FROM match_results WHERE run_id = ? ORDER BY rank", run);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("investor_id")).isEqualTo(strong);
        assertThat(((Number) rows.get(0).get("score")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) rows.get(1).get("score")).doubleValue()).isLessThan(50.0);
        assertThat(rows.get(1).get("explanation")).isEqualTo("Score 99, best ever!"); // text kept, number not trusted
    }

    @Test
    void runIsStoredAsPendingReviewWithUsageLatencyAndToolCalls() {
        givenNeedAndRun();
        UUID strong = investor("FINTECH,EUROPE,500000,2000000,MEDIUM");
        var calls = List.of(new RunToolbox.Call("scoreFit", "{\"investorId\":\"" + strong + "\"}", "{\"total\":100}", false, 3));

        service.claim(run, need);
        service.complete(run, need, new MatchAgent.Result(List.of(entry(strong, "ok")),
                new MatchAgent.Run("{}", 321, 45, 1500, calls)));

        Map<String, Object> r = db.queryForMap("SELECT status, tokens_in, tokens_out, latency_ms, tool_calls::text AS calls, reviewed_at"
                + " FROM match_runs WHERE id = ?", run);
        assertThat(r.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(r.get("tokens_in")).isEqualTo(321);
        assertThat(r.get("tokens_out")).isEqualTo(45);
        assertThat(r.get("latency_ms")).isEqualTo(1500);
        assertThat((String) r.get("calls")).contains("scoreFit").contains("total").contains("\"ms\": 3");
        assertThat(r.get("reviewed_at")).isNull(); // nobody reviewed it yet
    }

    @Test
    void completionQueuesAMatchCompletedEvent() {
        givenNeedAndRun();
        UUID strong = investor("FINTECH,EUROPE,500000,2000000,MEDIUM");
        service.claim(run, need);
        service.complete(run, need, new MatchAgent.Result(List.of(entry(strong, "ok")),
                new MatchAgent.Run("{}", 1, 1, 1, List.of())));
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic='match.completed' AND event_key=?",
                Integer.class, need.toString())).isEqualTo(1);
    }

    @Test
    void failureStoresASafeMessageAndNoResults() {
        givenNeedAndRun();
        service.claim(run, need);
        service.fail(run, need, "Model timed out after 60s");
        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, run)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT error FROM match_runs WHERE id=?", String.class, run)).isEqualTo("Model timed out after 60s");
        assertThat(db.queryForObject("SELECT count(*) FROM match_results WHERE run_id=?", Integer.class, run)).isZero();
    }

    // ---- stuck runs ----

    UUID runningSince(String ago) {
        return db.queryForObject("INSERT INTO match_runs(need_id,event_id,status,started_at) VALUES (?,?,'RUNNING', now() - ?::interval) RETURNING id",
                UUID.class, need, UUID.randomUUID(), ago);
    }

    @Test
    void reaperFailsOldRunningRunsAndLeavesFreshOnesAlone() {
        givenNeedAndRun();
        UUID old = runningSince("10 minutes");
        UUID fresh = runningSince("10 seconds");

        int reaped = service.reapStuck(java.time.Duration.ofMinutes(5));

        assertThat(reaped).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, old)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT error FROM match_runs WHERE id=?", String.class, old)).contains("abandoned");
        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, fresh)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic='match.completed' AND payload LIKE ?",
                Integer.class, "%" + old + "%")).isEqualTo(1);
    }

    @Test
    void reaperDoesNotTouchRunsInOtherStates() {
        givenNeedAndRun(); // run is REQUESTED
        assertThat(service.reapStuck(java.time.Duration.ofSeconds(1))).isZero();
        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, run)).isEqualTo("REQUESTED");
    }

    @Test
    void aRunThatFinishesAfterBeingReapedStaysFailedWithNoResults() {
        givenNeedAndRun();
        UUID strong = investor("FINTECH,EUROPE,500000,2000000,MEDIUM");
        UUID late = runningSince("10 minutes");
        service.reapStuck(java.time.Duration.ofMinutes(5));

        service.complete(late, need, new MatchAgent.Result(List.of(entry(strong, "too late")),
                new MatchAgent.Run("{}", 1, 1, 1, List.of())));

        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, late)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT count(*) FROM match_results WHERE run_id=?", Integer.class, late)).isZero();
    }

    @Test
    void claimRecordsWhenTheRunStarted() {
        givenNeedAndRun();
        assertThat(db.queryForObject("SELECT started_at FROM match_runs WHERE id=?", java.sql.Timestamp.class, run)).isNull();
        service.claim(run, need);
        assertThat(db.queryForObject("SELECT started_at FROM match_runs WHERE id=?", java.sql.Timestamp.class, run)).isNotNull();
    }

    // ---- a failed run must not leave the need stuck ----

    String needStatus() {
        return db.queryForObject("SELECT status FROM funding_needs WHERE id=?", String.class, need);
    }

    @Test
    void aFailedRunPutsTheNeedBackToSubmitted() {
        givenNeedAndRun();
        db.update("UPDATE funding_needs SET status='MATCHING' WHERE id=?", need);
        service.claim(run, need);
        service.fail(run, need, "Model timed out after 60s");
        assertThat(needStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void aReapedRunPutsTheNeedBackToSubmitted() {
        givenNeedAndRun();
        db.update("UPDATE funding_needs SET status='MATCHING' WHERE id=?", need);
        runningSince("10 minutes");
        service.reapStuck(java.time.Duration.ofMinutes(5));
        assertThat(needStatus()).isEqualTo("SUBMITTED");
    }
}
