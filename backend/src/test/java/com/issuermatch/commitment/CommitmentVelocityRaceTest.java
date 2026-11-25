package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.issuermatch.support.IntegrationTest;
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

/**
 * One investor firing many commitments at DIFFERENT needs at the same instant. The per-need lock does not
 * help here: the rules that count an investor's history (velocity, concentration) must see each other.
 */
@IntegrationTest
class CommitmentVelocityRaceTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    UUID investorId;
    final List<UUID> issuers = new ArrayList<>();
    final List<UUID> needs = new ArrayList<>();
    final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String k : keys) db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", k);
        for (UUID n : needs) db.update("DELETE FROM commitments WHERE need_id = ?", n);
        for (UUID i : issuers) db.update("DELETE FROM issuers WHERE id = ?", i);
        if (investorId != null) db.update("DELETE FROM investors WHERE id = ?", investorId);
    }

    void givenOneInvestorApprovedForManyNeeds(int count) {
        investorId = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Burst Inv " + UUID.randomUUID());
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',1,100000000,'MEDIUM','{EQUITY}')", investorId);
        for (int i = 0; i < count; i++) {
            UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                    UUID.class, "Burst " + UUID.randomUUID(), UUID.randomUUID());
            issuers.add(issuer);
            UUID need = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                    + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',10000000,'EQUITY','MEDIUM',10000000) RETURNING id",
                    UUID.class, issuer);
            needs.add(need);
            UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id",
                    UUID.class, need, UUID.randomUUID());
            db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,100,'ok')", run, investorId);
        }
    }

    @Test
    void aParallelBurstCannotSlipUnderTheVelocityLimits() throws Exception {
        givenOneInvestorApprovedForManyNeeds(15);
        ExecutorService pool = Executors.newFixedThreadPool(needs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (UUID need : needs) {
            String key = "burst-" + UUID.randomUUID();
            keys.add(key);
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/commitments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"needId\":\"" + need + "\",\"investorId\":\"" + investorId + "\",\"amount\":100000}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get());
        pool.shutdown();

        // velocity defaults: the 5th attempt is flagged, the 10th and later are blocked. Decided one after another:
        assertThat(statuses.stream().filter(s -> s == 201).count()).as("clean passes: attempts 1-4").isEqualTo(4);
        assertThat(statuses.stream().filter(s -> s == 202).count()).as("flagged: attempts 5-9").isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 422).count()).as("blocked: attempts 10-15").isEqualTo(6);
    }
}
