package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;

/** A raise can never be over-filled, even when many investors commit at the same moment. Committed rows, cleaned up. */
@IntegrationTest
class CommitmentCapacityTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    UUID issuerId;
    UUID needId;
    final List<UUID> investors = new ArrayList<>();
    final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String k : keys) db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", k);
        if (needId != null) db.update("DELETE FROM commitments WHERE need_id = ?", needId);
        if (issuerId != null) db.update("DELETE FROM issuers WHERE id = ?", issuerId);
        for (UUID i : investors) db.update("DELETE FROM investors WHERE id = ?", i);
    }

    /** A need raising `amount`, with `n` investors all approved for it. */
    void givenNeedWithApprovedInvestors(long amount, int n) {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Cap " + UUID.randomUUID(), UUID.randomUUID());
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',?,'EQUITY','MEDIUM',?) RETURNING id",
                UUID.class, issuerId, amount, amount);
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id",
                UUID.class, needId, UUID.randomUUID());
        for (int i = 0; i < n; i++) {
            UUID inv = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                    UUID.class, "Cap Inv " + UUID.randomUUID());
            investors.add(inv);
            db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                    + " VALUES (?,'FINTECH','EUROPE',1,100000000,'MEDIUM','{EQUITY}')", inv);
            db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,?,100,'ok')", run, inv, i + 1);
        }
    }

    ResultActions commit(UUID investor, long amount, String key) throws Exception {
        keys.add(key);
        return mvc.perform(post("/commitments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(
                "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investor + "\",\"amount\":" + amount + "}"));
    }

    BigDecimal remaining() {
        return db.queryForObject("SELECT remaining_capacity FROM funding_needs WHERE id = ?", BigDecimal.class, needId);
    }

    /** Money actually held: accepted or pending. Rejected commitments are recorded but hold nothing. */
    BigDecimal committed() {
        return db.queryForObject("SELECT coalesce(sum(amount),0) FROM commitments WHERE need_id = ?"
                + " AND status IN ('ACCEPTED','PENDING_REVIEW')", BigDecimal.class, needId);
    }

    @Test
    void acceptedCommitmentsReduceTheRemainingCapacity() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 1);
        commit(investors.get(0), 400_000, "cap-" + UUID.randomUUID()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingCapacity").value(600000));
        assertThat(remaining()).isEqualByComparingTo("600000");
        assertThat(db.queryForObject("SELECT version FROM funding_needs WHERE id = ?", Long.class, needId)).isEqualTo(1L);
    }

    @Test
    void aCommitmentAboveTheRemainingCapacityIsRefusedAndChangesNothing() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 1);
        commit(investors.get(0), 1_000_001, "cap-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisions[?(@.rule=='capacity')].verdict").value("BLOCK"));
        assertThat(remaining()).isEqualByComparingTo("1000000");
        assertThat(committed()).isEqualByComparingTo("0");
    }

    @Test
    void theExactRemainingAmountIsAllowedAndThenTheRaiseIsFull() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 1);
        // all of what is left is allowed, but taking 100% of it is flagged for a second look (capacity is held meanwhile)
        commit(investors.get(0), 1_000_000, "cap-" + UUID.randomUUID()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        assertThat(remaining()).isEqualByComparingTo("0");
        commit(investors.get(0), 1, "cap-" + UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aRetriedRequestDoesNotTakeCapacityTwice() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 1);
        String key = "cap-" + UUID.randomUUID();
        commit(investors.get(0), 300_000, key).andExpect(status().isCreated());
        commit(investors.get(0), 300_000, key).andExpect(status().isCreated()); // replay
        assertThat(remaining()).isEqualByComparingTo("700000");
        assertThat(committed()).isEqualByComparingTo("300000");
    }

    @Test
    void twoInvestorsRacingForTheLastCapacityOnlyOneWins() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 2);
        List<Integer> statuses = runInParallel(List.of(
                () -> commit(investors.get(0), 700_000, "race-a-" + UUID.randomUUID()).andReturn().getResponse().getStatus(),
                () -> commit(investors.get(1), 700_000, "race-b-" + UUID.randomUUID()).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 422); // the loser is blocked by the capacity rule
        assertThat(committed()).isEqualByComparingTo("700000"); // never 1,400,000
        assertThat(remaining()).isEqualByComparingTo("300000");
    }

    @Test
    void manyParallelCommitmentsNeverOverFillTheRaise() throws Exception {
        givenNeedWithApprovedInvestors(1_000_000, 20);
        List<java.util.concurrent.Callable<Integer>> jobs = new ArrayList<>();
        for (UUID inv : investors) {
            jobs.add(() -> commit(inv, 100_000, "many-" + UUID.randomUUID()).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runInParallel(jobs);

        // 9 pass cleanly; the 10th takes the last 100k (more than 90% of what is left) so it is flagged; 10 are blocked
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(9);
        assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(10);
        assertThat(committed()).isEqualByComparingTo("1000000");
        assertThat(remaining()).isEqualByComparingTo("0");
    }

    private <T> List<T> runInParallel(List<java.util.concurrent.Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                start.await();
                return job.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        pool.shutdown();
        return results;
    }
}
