package com.issuermatch.deal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.Deals;
import com.issuermatch.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** An investor offers on a matched deal through the same safeguarded commitment endpoint. */
@IntegrationTest
class InvestorOfferTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    Deals deal;

    @AfterEach
    void cleanUp() {
        if (deal != null) deal.cleanUp();
    }

    static String offer(UUID need, UUID investor, long amount, String extra) {
        return "{\"needId\":\"" + need + "\",\"investorId\":\"" + investor + "\",\"amount\":" + amount + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static final String WHY = "\"investmentRationale\":\"We like the team and the traction.\"";

    ResultActions send(RequestPostProcessor user, String key, String body) throws Exception {
        return mvc.perform(post("/commitments").with(user).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String key() {
        return "deal-" + UUID.randomUUID();
    }

    int commitments() {
        return db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ?", Integer.class, deal.needId);
    }

    @Test
    void aMatchedInvestorOffersWithStakeTermsAndRationaleAndTheIssuerSeesIt() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        String letter = "Dear founder, our Managing Director is ready to lead this investment.";
        String extra = WHY + ",\"proposedStakePct\":12.5,\"proposedTerms\":{\"valuation\":8000000,\"conditions\":\"Board observer seat\"}"
                + ",\"offerLetter\":\"" + letter + "\"";
        send(Auth.investor(ivy.user()), key(), offer(deal.needId, ivy.investorId(), 500_000, extra))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.offerLetter").value(letter))
                .andExpect(jsonPath("$.proposedStakePct").value(12.5))
                .andExpect(jsonPath("$.decisions", hasSize(0))); // internal risk reasoning is not shown to the investor

        var row = db.queryForMap("SELECT investor_decision, proposed_stake_pct::text AS stake, proposed_terms_json->>'valuation' AS v,"
                + " investment_rationale, created_by FROM commitments WHERE need_id = ?", deal.needId);
        assertThat(row.get("investor_decision")).isEqualTo("INTERESTED");
        assertThat(row.get("stake")).isEqualTo("12.500");
        assertThat(row.get("v")).isEqualTo("8000000");
        assertThat(row.get("created_by")).isEqualTo(ivy.user());
        assertThat(db.queryForObject("SELECT investor_decision FROM match_results WHERE id = ?", String.class, ivy.matchResultId())).isEqualTo("INTERESTED");
        assertThat(db.queryForObject("SELECT count(*) FROM risk_decisions d JOIN commitments c ON c.id = d.commitment_id WHERE c.need_id = ?",
                Integer.class, deal.needId)).isEqualTo(5); // every rule still ran and was stored
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic = 'commitment.requested' AND event_key = ?", Integer.class,
                deal.needId.toString())).isEqualTo(1);

        mvc.perform(get("/funding-needs/" + deal.needId + "/offers").with(Auth.issuer(deal.issuerUser))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].investorFirm").value(org.hamcrest.Matchers.startsWith("Deal Fund")))
                .andExpect(jsonPath("$[0].contactRole").value("Managing Partner"))
                .andExpect(jsonPath("$[0].amount").value(500000))
                .andExpect(jsonPath("$[0].proposedStakePct").value(12.5))
                .andExpect(jsonPath("$[0].proposedTerms.conditions").value("Board observer seat"))
                .andExpect(jsonPath("$[0].investmentRationale").value("We like the team and the traction."))
                .andExpect(jsonPath("$[0].offerLetter").value(letter));
        mvc.perform(get("/investor/offers").with(Auth.investor(ivy.user()))).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
    }

    @Test
    void anInvestorCanOnlyOfferAsItselfOnDealsItWasApprovedFor() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        Deals.Party otto = deal.investor("APPROVED");
        Deals.Party unmatched = deal.investor(null);
        Deals.Party pending = deal.investor("PENDING_REVIEW");
        // pretending to be another investor
        send(Auth.investor(ivy.user()), key(), offer(deal.needId, otto.investorId(), 500_000, WHY)).andExpect(status().isNotFound());
        // never matched, or not approved yet: the deal does not exist as far as they can tell
        send(Auth.investor(unmatched.user()), key(), offer(deal.needId, unmatched.investorId(), 500_000, WHY)).andExpect(status().isNotFound());
        send(Auth.investor(pending.user()), key(), offer(deal.needId, pending.investorId(), 500_000, WHY)).andExpect(status().isNotFound());
        // an investor user with no profile
        send(Auth.investor(UUID.randomUUID()), key(), offer(deal.needId, ivy.investorId(), 500_000, WHY)).andExpect(status().isNotFound());
        // issuers cannot commit
        send(Auth.issuer(deal.issuerUser), key(), offer(deal.needId, ivy.investorId(), 500_000, WHY)).andExpect(status().isForbidden());
        assertThat(commitments()).isZero();
    }

    @Test
    void offersNeedARationaleAndStakeOnlyMakesSenseForEquity() throws Exception {
        deal = new Deals(db, "DEBT");
        Deals.Party ivy = deal.investor("APPROVED");
        var me = Auth.investor(ivy.user());
        send(me, key(), offer(deal.needId, ivy.investorId(), 500_000, "")).andExpect(status().isBadRequest());
        send(me, key(), offer(deal.needId, ivy.investorId(), 500_000, "\"investmentRationale\":\"  \"")).andExpect(status().isBadRequest());
        send(me, key(), offer(deal.needId, ivy.investorId(), 500_000, WHY + ",\"proposedStakePct\":5")).andExpect(status().isBadRequest());
        for (String bad : new String[] {"\"proposedStakePct\":0", "\"proposedStakePct\":101", "\"proposedTerms\":{\"nope\":1}",
                "\"proposedTerms\":{\"interestRatePct\":500}", "\"proposedTerms\":{\"tenorMonths\":0}",
                "\"investmentRationale\":\"" + "x".repeat(5001) + "\"",
                "\"offerLetter\":\"" + "x".repeat(5001) + "\""}) {
            send(me, key(), offer(deal.needId, ivy.investorId(), 500_000, bad.startsWith("\"investmentRationale") ? bad : WHY + "," + bad))
                    .andExpect(status().isBadRequest());
        }
        send(me, key(), offer(deal.needId, ivy.investorId(), 500_000, WHY + ",\"proposedTerms\":{\"interestRatePct\":9.5,\"tenorMonths\":36}"))
                .andExpect(status().isCreated());
    }

    @Test
    void idempotencyCoversTheNewFieldsAndFiftyParallelRetriesMakeOneOffer() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        var me = Auth.investor(ivy.user());
        String k = key();
        String body = offer(deal.needId, ivy.investorId(), 400_000, WHY + ",\"proposedStakePct\":10");
        var pool = Executors.newFixedThreadPool(50);
        var start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return send(me, k, body).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (var f : results) assertThat(f.get()).isEqualTo(201);
        pool.shutdown();
        assertThat(commitments()).isEqualTo(1);
        // same key, same amount, different terms or rationale: a different request
        send(me, k, offer(deal.needId, ivy.investorId(), 400_000, WHY + ",\"proposedStakePct\":20")).andExpect(status().isUnprocessableEntity());
        send(me, k, offer(deal.needId, ivy.investorId(), 400_000, "\"investmentRationale\":\"other\",\"proposedStakePct\":10"))
                .andExpect(status().isUnprocessableEntity());
        send(me, k, offer(deal.needId, ivy.investorId(), 400_000, WHY + ",\"proposedStakePct\":10,\"offerLetter\":\"different\""))
                .andExpect(status().isUnprocessableEntity());
        assertThat(db.queryForObject("SELECT remaining_capacity::text FROM funding_needs WHERE id = ?", String.class, deal.needId))
                .isEqualTo("9600000.00");
    }

    @Test
    void aBlockedOfferIsNotShownToTheIssuerAndHoldsNothing() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        send(Auth.investor(ivy.user()), key(), offer(deal.needId, ivy.investorId(), 9_000_000, WHY)) // far above the 2M mandate
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisions", hasSize(0)));
        mvc.perform(get("/funding-needs/" + deal.needId + "/offers").with(Auth.issuer(deal.issuerUser))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/investor/offers").with(Auth.investor(ivy.user()))).andExpect(jsonPath("$[0].status").value("REJECTED"));
    }

    @Test
    void offersAreVisibleOnlyToTheOwningIssuerStaffAndTheOfferingInvestor() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        Deals.Party otto = deal.investor("APPROVED");
        send(Auth.investor(ivy.user()), key(), offer(deal.needId, ivy.investorId(), 500_000, WHY)).andExpect(status().isCreated());
        String offers = "/funding-needs/" + deal.needId + "/offers";
        mvc.perform(get(offers).with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(get(offers).with(Auth.investor(otto.user()))).andExpect(status().isForbidden());
        mvc.perform(get(offers).with(Auth.analyst())).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/investor/offers").with(Auth.investor(otto.user()))).andExpect(jsonPath("$", hasSize(0)));
        // the staff commitment queue stays closed to investors, even for their own offer
        mvc.perform(get("/commitments").with(Auth.investor(ivy.user()))).andExpect(status().isForbidden());
        String id = db.queryForObject("SELECT id::text FROM commitments WHERE need_id = ?", String.class, deal.needId);
        mvc.perform(get("/commitments/" + id).with(Auth.investor(ivy.user()))).andExpect(status().isForbidden());
        mvc.perform(post("/commitments/" + id + "/approve").with(Auth.investor(ivy.user()))).andExpect(status().isForbidden());
        mvc.perform(post("/commitments/" + id + "/reject").with(Auth.investor(otto.user()))).andExpect(status().isForbidden());
    }

    // ---- passing ----

    ResultActions pass(RequestPostProcessor user, UUID result, String body) throws Exception {
        return mvc.perform(post("/investor/matches/" + result + "/pass").with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anInvestorCanPassWithAReasonOnItsOwnMatchOnly() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        Deals.Party otto = deal.investor("APPROVED");
        Deals.Party pending = deal.investor("PENDING_REVIEW");
        pass(Auth.investor(otto.user()), ivy.matchResultId(), "{}").andExpect(status().isNotFound());
        pass(Auth.investor(pending.user()), pending.matchResultId(), "{}").andExpect(status().isNotFound());
        pass(Auth.analyst(), ivy.matchResultId(), "{}").andExpect(status().isForbidden());
        pass(Auth.investor(ivy.user()), ivy.matchResultId(), "{\"reason\":\"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest());

        pass(Auth.investor(ivy.user()), ivy.matchResultId(), "{\"reason\":\"Valuation too high\"}").andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT investor_decision || ':' || pass_reason FROM match_results WHERE id = ?", String.class,
                ivy.matchResultId())).isEqualTo("PASSED:Valuation too high");
        mvc.perform(get("/investor/matches").with(Auth.investor(ivy.user())))
                .andExpect(jsonPath("$[0].investorDecision").value("PASSED")).andExpect(jsonPath("$[0].passReason").value("Valuation too high"));
        pass(Auth.investor(otto.user()), otto.matchResultId(), "{}").andExpect(status().isNoContent()); // the reason is optional
    }

    @Test
    void youCannotPassOnADealYouHaveALiveOfferFor() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        send(Auth.investor(ivy.user()), key(), offer(deal.needId, ivy.investorId(), 500_000, WHY)).andExpect(status().isCreated());
        pass(Auth.investor(ivy.user()), ivy.matchResultId(), "{}").andExpect(status().isConflict());
    }
}
