package com.issuermatch.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.reconciliation.ReconciliationService;
import com.issuermatch.support.Auth;
import com.issuermatch.support.Deals;
import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The company's answer to an accepted offer: accept it, or decline it and get the capacity back. */
@IntegrationTest
class IssuerDecisionTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ReconciliationService reconciliation;

    Deals deal;

    @AfterEach
    void cleanUp() {
        if (deal == null) return;
        // let every queued event reach the ledger first, or it would arrive after the commitments are gone
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL AND event_key = ?", Integer.class,
                deal.needId.toString()) == 0);
        await().atMost(Duration.ofSeconds(30)).until(() -> ledgerRows() == db.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic LIKE 'commitment.%' AND event_key = ?", Integer.class,
                deal.needId.toString()));
        deal.cleanUp();
    }

    int ledgerRows() {
        return db.queryForObject("SELECT count(*) FROM commitment_events WHERE need_id = ?", Integer.class, deal.needId);
    }

    UUID offer(Deals.Party party, long amount, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/commitments").with(Auth.investor(party.user())).header("Idempotency-Key", "deal-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"needId\":\"" + deal.needId + "\",\"investorId\":\""
                                + party.investorId() + "\",\"amount\":" + amount + ",\"investmentRationale\":\"Good team.\"}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1"));
    }

    ResultActions decide(RequestPostProcessor user, UUID commitment, String verb) throws Exception {
        return mvc.perform(post("/commitments/" + commitment + "/issuer-" + verb).with(user));
    }

    BigDecimal remaining() {
        return db.queryForObject("SELECT remaining_capacity FROM funding_needs WHERE id = ?", BigDecimal.class, deal.needId);
    }

    List<String> mismatches() {
        // age the rows past the grace period, so a missing event would be reported
        db.update("UPDATE commitments SET created_at = now() - interval '1 hour',"
                + " issuer_decided_at = issuer_decided_at - interval '1 hour' WHERE need_id = ?", deal.needId);
        return reconciliation.run(null).mismatches().stream().filter(m -> deal.needId.equals(m.needId()))
                .map(m -> m.type() + ": " + m.detail()).toList();
    }

    @Test
    void acceptingKeepsTheCapacityAndBothSidesSeeTheAnswer() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        UUID commitment = offer(ivy, 500_000, 201);

        decide(Auth.issuer(deal.issuerUser), commitment, "accept").andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerDecision").value("ACCEPTED")).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.remainingCapacity").value(9_500_000))
                .andExpect(jsonPath("$.decisions").doesNotExist()); // risk reasoning never reaches the issuer

        assertThat(remaining()).isEqualByComparingTo("9500000");
        mvc.perform(get("/funding-needs/" + deal.needId + "/offers").with(Auth.issuer(deal.issuerUser)))
                .andExpect(jsonPath("$[0].issuerDecision").value("ACCEPTED"));
        mvc.perform(get("/investor/offers").with(Auth.investor(ivy.user())))
                .andExpect(jsonPath("$[0].issuerDecision").value("ACCEPTED"));
        assertThat(db.queryForObject("SELECT issuer_decided_by FROM commitments WHERE id = ?", UUID.class, commitment))
                .isEqualTo(deal.issuerUser);
        assertThat(db.queryForList("SELECT action FROM audit_log WHERE resource = ? AND actor = ?", String.class,
                "commitment:" + commitment, deal.issuerUser.toString())).containsExactly("COMMITMENT_ISSUER_ACCEPTED");
        await().atMost(Duration.ofSeconds(30)).until(() -> ledgerRows() == 2);
        assertThat(mismatches()).isEmpty();

        // final: neither answer can follow
        decide(Auth.issuer(deal.issuerUser), commitment, "accept").andExpect(status().isConflict());
        decide(Auth.issuer(deal.issuerUser), commitment, "decline").andExpect(status().isConflict());
        assertThat(remaining()).isEqualByComparingTo("9500000");
    }

    @Test
    void decliningReleasesTheCapacitySendsAnEventAndReconcilesCleanly() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        UUID commitment = offer(ivy, 500_000, 201);

        decide(Auth.issuer(deal.issuerUser), commitment, "decline").andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerDecision").value("DECLINED"))
                .andExpect(jsonPath("$.remainingCapacity").value(10_000_000));

        assertThat(remaining()).isEqualByComparingTo("10000000");
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM commitment_events WHERE commitment_id = ? AND type = 'DECLINED'", Integer.class, commitment) == 1);
        assertThat(mismatches()).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic = 'audit.events' AND payload LIKE ?", Integer.class,
                "%COMMITMENT_ISSUER_DECLINED%" + commitment + "%") + db.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'audit.events' AND payload LIKE ?", Integer.class,
                "%" + commitment + "%COMMITMENT_ISSUER_DECLINED%")).isEqualTo(1);
        mvc.perform(get("/investor/offers").with(Auth.investor(ivy.user())))
                .andExpect(jsonPath("$[0].issuerDecision").value("DECLINED"));

        decide(Auth.issuer(deal.issuerUser), commitment, "decline").andExpect(status().isConflict());
        decide(Auth.issuer(deal.issuerUser), commitment, "accept").andExpect(status().isConflict());
        assertThat(remaining()).isEqualByComparingTo("10000000");

        // a declined offer is no longer live: the investor may pass on the deal
        mvc.perform(post("/investor/matches/" + ivy.matchResultId() + "/pass").with(Auth.investor(ivy.user()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
    }

    @Test
    void onlyTheCompanyRaisingTheMoneyMayDecide() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        UUID commitment = offer(ivy, 500_000, 201);

        for (String verb : new String[] {"accept", "decline"}) {
            decide(Auth.issuer(UUID.randomUUID()), commitment, verb).andExpect(status().isNotFound());
            decide(Auth.issuer(deal.issuerUser), UUID.randomUUID(), verb).andExpect(status().isNotFound());
            for (RequestPostProcessor other : new RequestPostProcessor[] {Auth.investor(ivy.user()), Auth.analyst(), Auth.admin()}) {
                decide(other, commitment, verb).andExpect(status().isForbidden());
            }
            decide(Auth.anonymous(), commitment, verb).andExpect(status().isUnauthorized());
        }
        assertThat(db.queryForObject("SELECT issuer_decision FROM commitments WHERE id = ?", String.class, commitment))
                .isEqualTo("PENDING");
        assertThat(remaining()).isEqualByComparingTo("9500000");
    }

    @Test
    void anOfferStillInReviewOrRejectedCannotBeDecided() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        UUID commitment = offer(ivy, 500_000, 201);
        for (String notAccepted : new String[] {"PENDING_REVIEW", "REJECTED"}) {
            db.update("UPDATE commitments SET status = ? WHERE id = ?", notAccepted, commitment);
            decide(Auth.issuer(deal.issuerUser), commitment, "accept").andExpect(status().isConflict());
            decide(Auth.issuer(deal.issuerUser), commitment, "decline").andExpect(status().isConflict());
        }
        db.update("UPDATE commitments SET status = 'ACCEPTED' WHERE id = ?", commitment);
        assertThat(remaining()).isEqualByComparingTo("9500000");
    }

    @Test
    void twoDeclinesAtOnceReleaseTheCapacityOnce() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        UUID commitment = offer(ivy, 500_000, 201);

        List<CompletableFuture<Integer>> calls = java.util.stream.IntStream.range(0, 8).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
                return decide(Auth.issuer(deal.issuerUser), commitment, "decline").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();

        assertThat(calls.stream().map(CompletableFuture::join).sorted().toList())
                .containsExactly(200, 409, 409, 409, 409, 409, 409, 409);
        assertThat(remaining()).isEqualByComparingTo("10000000");
    }
}
