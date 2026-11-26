package com.issuermatch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Deliberately broken data is seeded, and the job must name each problem exactly. Correct data must stay silent. */
@IntegrationTest
class ReconciliationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ReconciliationService reconciliation;

    final List<UUID> issuers = new ArrayList<>();
    final List<UUID> needs = new ArrayList<>();
    UUID investorId;

    @AfterEach
    void cleanUp() {
        db.update("DELETE FROM reconciliation_runs");
        for (UUID n : needs) {
            db.update("DELETE FROM commitment_events WHERE need_id = ?", n);
            db.update("DELETE FROM commitments WHERE need_id = ?", n);
        }
        for (UUID i : issuers) db.update("DELETE FROM issuers WHERE id = ?", i);
        if (investorId != null) db.update("DELETE FROM investors WHERE id = ?", investorId);
    }

    UUID investor() {
        if (investorId == null) {
            investorId = db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                    UUID.class, "Rec Inv " + UUID.randomUUID());
        }
        return investorId;
    }

    /** A need for 1000 whose remaining capacity is `remaining`. */
    UUID need(long remaining) {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Rec " + UUID.randomUUID(), UUID.randomUUID());
        issuers.add(issuer);
        UUID need = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1000,'EQUITY','LOW',?) RETURNING id",
                UUID.class, issuer, remaining);
        needs.add(need);
        return need;
    }

    UUID commitment(UUID need, long amount, String status, String age) {
        return db.queryForObject("INSERT INTO commitments(need_id,investor_id,amount,status,created_at)"
                + " VALUES (?,?,?,?, now() - ?::interval) RETURNING id", UUID.class, need, investor(), amount, status, age);
    }

    void event(UUID commitment, UUID need, long amount, String type) {
        db.update("INSERT INTO commitment_events(event_id,commitment_id,need_id,investor_id,amount,type,occurred_at)"
                + " VALUES (gen_random_uuid(),?,?,?,?,?,now())", commitment, need, investor(), amount, type);
    }

    /** a consistent accepted commitment: capacity, status and both events agree */
    UUID healthyCommitment(UUID need, long amount) {
        UUID c = commitment(need, amount, "ACCEPTED", "1 hour");
        event(c, need, amount, "REQUESTED");
        event(c, need, amount, "ACCEPTED");
        return c;
    }

    List<String> typesForNeed(ReconciliationService.Report report, UUID need) {
        return report.mismatches().stream().filter(m -> need.equals(m.needId())).map(ReconciliationService.Mismatch::type)
                .sorted().toList();
    }

    @Test
    void healthyDataReportsNothing() {
        UUID n = need(900);
        healthyCommitment(n, 100);
        assertThat(typesForNeed(reconciliation.run(null), n)).isEmpty();
    }

    @Test
    void catchesTotalsThatDoNotAddUp() {
        UUID n = need(500);          // 500 of 1000 gone...
        healthyCommitment(n, 100);   // ...but only 100 is held
        ReconciliationService.Report report = reconciliation.run(null);
        assertThat(typesForNeed(report, n)).containsExactly("CAPACITY_MISMATCH");
        assertThat(report.mismatches()).filteredOn(m -> n.equals(m.needId())).singleElement()
                .satisfies(m -> assertThat(m.detail()).contains("500").contains("100"));
    }

    @Test
    void catchesAMissingEvent() {
        UUID n = need(900);
        UUID c = commitment(n, 100, "ACCEPTED", "1 hour");
        event(c, n, 100, "REQUESTED"); // the ACCEPTED event never arrived
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("MISSING_EVENT");
    }

    @Test
    void catchesADoubleCountedEvent() {
        UUID n = need(900);
        UUID c = healthyCommitment(n, 100);
        event(c, n, 100, "ACCEPTED"); // a second ACCEPTED event under a different event id
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("DUPLICATE_EVENT");
    }

    @Test
    void catchesAnEventForACommitmentThatDoesNotExist() {
        UUID n = need(1000);
        UUID ghost = UUID.randomUUID();
        event(ghost, n, 100, "ACCEPTED");
        ReconciliationService.Report report = reconciliation.run(null);
        assertThat(typesForNeed(report, n)).containsExactly("UNEXPECTED_EVENT");
        assertThat(report.mismatches()).anySatisfy(m -> assertThat(m.commitmentId()).isEqualTo(ghost));
    }

    @Test
    void catchesAnAcceptedEventForARejectedCommitment() {
        UUID n = need(1000);
        UUID c = commitment(n, 100, "REJECTED", "1 hour");
        event(c, n, 100, "REQUESTED");
        event(c, n, 100, "REJECTED");
        event(c, n, 100, "ACCEPTED"); // contradicts the record
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("UNEXPECTED_EVENT");
    }

    void declinedByIssuer(UUID commitment) {
        db.update("UPDATE commitments SET issuer_decision = 'DECLINED', issuer_decided_at = now() - interval '1 hour',"
                + " issuer_decided_by = ? WHERE id = ?", UUID.randomUUID(), commitment);
    }

    @Test
    void aDeclinedCommitmentHoldsNothingAndIsHealthyWithItsEvent() {
        UUID n = need(1000); // the whole raise is open again
        UUID c = healthyCommitment(n, 100);
        declinedByIssuer(c);
        event(c, n, 100, "DECLINED");
        assertThat(typesForNeed(reconciliation.run(null), n)).isEmpty();
    }

    @Test
    void catchesADeclineWhoseCapacityOrEventIsMissing() {
        UUID n = need(900); // still shows 100 as taken
        declinedByIssuer(healthyCommitment(n, 100));
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("CAPACITY_MISMATCH", "MISSING_EVENT");
    }

    @Test
    void catchesADeclinedEventForAnOfferNobodyDeclined() {
        UUID n = need(900);
        UUID c = healthyCommitment(n, 100);
        event(c, n, 100, "DECLINED");
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("UNEXPECTED_EVENT");
    }

    @Test
    void catchesAnEventWithTheWrongAmount() {
        UUID n = need(900);
        UUID c = commitment(n, 100, "ACCEPTED", "1 hour");
        event(c, n, 100, "REQUESTED");
        event(c, n, 999, "ACCEPTED");
        assertThat(typesForNeed(reconciliation.run(null), n)).containsExactly("AMOUNT_MISMATCH");
    }

    @Test
    void doesNotCryWolfAboutEventsStillInFlight() {
        UUID n = need(900);
        commitment(n, 100, "ACCEPTED", "1 second"); // events not delivered yet: normal
        assertThat(typesForNeed(reconciliation.run(null), n)).isEmpty();
    }

    @Test
    void aRealCommitmentThroughTheApiAndKafkaReconcilesCleanly() throws Exception {
        UUID n = need(1000);
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',1,100000,'LOW','{EQUITY}')", investor());
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id",
                UUID.class, n, UUID.randomUUID());
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,100,'ok')", run, investor());
        String key = "rec-" + UUID.randomUUID();
        mvc.perform(post("/commitments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(
                "{\"needId\":\"" + n + "\",\"investorId\":\"" + investor() + "\",\"amount\":250}")).andExpect(status().isCreated());
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM commitment_events WHERE need_id = ?", Integer.class, n) == 2);
        db.update("UPDATE commitments SET created_at = now() - interval '1 hour' WHERE need_id = ?", n); // past the grace period

        assertThat(typesForNeed(reconciliation.run(null), n)).isEmpty();
        db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", key);
    }

    @Test
    void adminsCanRunItAndReadTheLatestReport() throws Exception {
        db.update("DELETE FROM reconciliation_runs");
        mvc.perform(get("/admin/reconciliation/latest").with(Auth.admin())).andExpect(status().isNotFound());

        UUID n = need(500);
        UUID carol = UUID.randomUUID();
        mvc.perform(post("/admin/reconciliation/run").with(Auth.as(carol, "ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.triggeredBy").value(carol.toString()))
                .andExpect(jsonPath("$.countsByType.CAPACITY_MISMATCH").exists());
        mvc.perform(get("/admin/reconciliation/latest").with(Auth.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount").isNumber())
                .andExpect(jsonPath("$.mismatches[?(@.needId=='" + n + "')].type").value("CAPACITY_MISMATCH"));
    }

    @Test
    void onlyAdminsMayRunOrReadIt() throws Exception {
        for (RequestPostProcessor user : new RequestPostProcessor[] {Auth.analyst(), Auth.issuer(UUID.randomUUID()), Auth.noRoles()}) {
            mvc.perform(post("/admin/reconciliation/run").with(user)).andExpect(status().isForbidden());
            mvc.perform(get("/admin/reconciliation/latest").with(user)).andExpect(status().isForbidden());
        }
    }
}
