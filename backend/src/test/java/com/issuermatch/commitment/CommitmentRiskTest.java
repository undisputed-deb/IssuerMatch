package com.issuermatch.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The risk engine through the real API. The investor's mandate is 100k-1M, the raise is 10M.
 * 500k passes, 1.2M is 20% above the mandate (flag), 2M is 100% above (block, default limit 50%).
 */
@IntegrationTest
class CommitmentRiskTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    UUID issuerId;
    UUID needId;
    UUID investorId;
    final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String k : keys) db.update("DELETE FROM idempotency_keys WHERE idem_key = ?", k);
        db.update("DELETE FROM risk_rule_config"); // tests tune rules; leave the defaults for everyone else
        if (needId != null) db.update("DELETE FROM commitments WHERE need_id = ?", needId); // cascades decisions
        if (issuerId != null) db.update("DELETE FROM issuers WHERE id = ?", issuerId);
        if (investorId != null) db.update("DELETE FROM investors WHERE id = ?", investorId);
    }

    void givenApprovedMatch() {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Risk " + UUID.randomUUID(), UUID.randomUUID());
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',10000000,'EQUITY','MEDIUM',10000000) RETURNING id",
                UUID.class, issuerId);
        investorId = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Risk Inv " + UUID.randomUUID());
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',100000,1000000,'MEDIUM','{EQUITY}')", investorId);
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id",
                UUID.class, needId, UUID.randomUUID());
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,100,'ok')", run, investorId);
    }

    ResultActions commit(long amount, String key, RequestPostProcessor user) throws Exception {
        keys.add(key);
        return mvc.perform(post("/commitments").with(user).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\",\"amount\":" + amount + "}"));
    }

    ResultActions commit(long amount) throws Exception {
        return commit(amount, "risk-" + UUID.randomUUID(), Auth.admin());
    }

    String idOf(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
    }

    BigDecimal remaining() {
        return db.queryForObject("SELECT remaining_capacity FROM funding_needs WHERE id = ?", BigDecimal.class, needId);
    }

    ResultActions tune(String rule, boolean enabled, String paramsJson) throws Exception {
        return mvc.perform(put("/admin/risk-rules/" + rule).with(Auth.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + ",\"params\":" + paramsJson + "}"));
    }

    // ---------- outcomes ----------

    @Test
    void passMeansAcceptedAndEveryRuleExplainsItself() throws Exception {
        givenApprovedMatch();
        commit(500_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decisions", hasSize(5)))
                .andExpect(jsonPath("$.decisions[?(@.verdict != 'PASS')]").isEmpty())
                .andExpect(jsonPath("$.decisions[0].reason").isNotEmpty());
        assertThat(db.queryForObject("SELECT count(*) FROM risk_decisions d JOIN commitments c ON c.id = d.commitment_id WHERE c.need_id = ?",
                Integer.class, needId)).isEqualTo(5);
        assertThat(remaining()).isEqualByComparingTo("9500000");
    }

    @Test
    void aFlagIsRecordedPendingReviewAndHoldsTheCapacity() throws Exception {
        givenApprovedMatch();
        commit(1_200_000).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.decisions[?(@.rule=='ticket-range')].verdict").value("FLAG"))
                .andExpect(jsonPath("$.decisions[?(@.rule=='ticket-range')].reason").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("20.0% outside"))));
        assertThat(remaining()).isEqualByComparingTo("8800000"); // held while it waits, so nobody can overtake it
    }

    @Test
    void aBlockIsRecordedRejectedWithReasonsAndHoldsNothing() throws Exception {
        givenApprovedMatch();
        commit(2_000_000).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisions[?(@.rule=='ticket-range')].verdict").value("BLOCK"));
        assertThat(remaining()).isEqualByComparingTo("10000000");
        // the rejection is stored, with its decisions, so it can be explained later
        assertThat(db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ? AND status = 'REJECTED'", Integer.class, needId)).isEqualTo(1);
    }

    @Test
    void aRetriedBlockedRequestReplaysTheSameRejection() throws Exception {
        givenApprovedMatch();
        String key = "risk-" + UUID.randomUUID();
        String first = commit(2_000_000, key, Auth.admin()).andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        String second = commit(2_000_000, key, Auth.admin()).andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ?", Integer.class, needId)).isEqualTo(1);
    }

    @Test
    void anInvestorWithNoMandateForTheNeedIsFlagged() throws Exception {
        givenApprovedMatch();
        db.update("DELETE FROM mandates WHERE investor_id = ?", investorId);
        commit(500_000).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decisions[?(@.rule=='ticket-range')].reason").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("no mandate"))));
    }

    @Test
    void repeatingTheSameAmountIsFlaggedThenBlocked() throws Exception {
        givenApprovedMatch();
        commit(300_000).andExpect(status().isCreated());
        commit(300_000).andExpect(status().isAccepted());                 // 1 identical earlier: flag
        commit(300_000).andExpect(status().isAccepted());                 // 2 earlier: still flag
        commit(300_000).andExpect(status().isUnprocessableEntity())       // 3 earlier: block
                .andExpect(jsonPath("$.decisions[?(@.rule=='duplicate')].verdict").value("BLOCK"));
    }

    @Test
    void aBurstFromOneInvestorTripsTheVelocityRule() throws Exception {
        givenApprovedMatch();
        for (int i = 1; i <= 4; i++) commit(100_000 + i * 1000).andExpect(status().isCreated());   // 4 attempts, all different amounts
        commit(110_000).andExpect(status().isAccepted())                                           // the 5th: flag
                .andExpect(jsonPath("$.decisions[?(@.rule=='velocity')].verdict").value("FLAG"));
    }

    @Test
    void exposureToOneIssuerAcrossCommitmentsTripsTheConcentrationRule() throws Exception {
        givenApprovedMatch();
        db.update("UPDATE mandates SET max_ticket = 50000000 WHERE investor_id = ?", investorId); // keep the ticket rule quiet
        commit(4_000_000).andExpect(status().isCreated());                    // 4M total
        commit(1_500_000).andExpect(status().isAccepted())                    // 5.5M: above 5M, flag
                .andExpect(jsonPath("$.decisions[?(@.rule=='concentration')].verdict").value("FLAG"));
        commit(4_600_000).andExpect(status().isUnprocessableEntity())         // 10.1M: above 10M, block
                .andExpect(jsonPath("$.decisions[?(@.rule=='concentration')].verdict").value("BLOCK"));
    }

    // ---------- dynamic: tune rules at runtime ----------

    @Test
    void anAdminCanLoosenARuleAndTheNextCommitmentUsesIt() throws Exception {
        givenApprovedMatch();
        commit(2_000_000).andExpect(status().isUnprocessableEntity());                       // default: blocked
        tune("ticket-range", true, "{\"blockOutsidePct\":500}").andExpect(status().isOk())
                .andExpect(jsonPath("$.params.blockOutsidePct").value(500));
        commit(2_000_000).andExpect(status().isAccepted());                                 // now only flagged
    }

    @Test
    void anAdminCanSwitchARuleOffAndItIsNoLongerEvaluated() throws Exception {
        givenApprovedMatch();
        tune("ticket-range", false, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        commit(2_000_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisions", hasSize(4)))
                .andExpect(jsonPath("$.decisions[?(@.rule=='ticket-range')]").isEmpty());
    }

    @Test
    void eachDecisionKeepsTheThresholdsThatWereInForceAtTheTime() throws Exception {
        givenApprovedMatch();
        String old = idOf(commit(500_000));
        tune("ticket-range", true, "{\"blockOutsidePct\":500}").andExpect(status().isOk());
        String recent = idOf(commit(520_000));

        String sql = "SELECT params->>'blockOutsidePct' FROM risk_decisions WHERE commitment_id = ?::uuid AND rule_id = 'ticket-range'";
        assertThat(db.queryForObject(sql, String.class, old)).isEqualTo("50");      // explainable even after the change
        assertThat(db.queryForObject(sql, String.class, recent)).isEqualTo("500");
    }

    // ---------- the admin API ----------

    @Test
    void listsEveryRuleWithItsDefaultsAndEffectiveSettings() throws Exception {
        mvc.perform(get("/admin/risk-rules").with(Auth.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains("capacity", "concentration", "duplicate", "ticket-range", "velocity")))
                .andExpect(jsonPath("$[?(@.id=='concentration')].defaults.flagAbove").value(5000000))
                .andExpect(jsonPath("$[?(@.id=='concentration')].enabled").value(true));
    }

    @Test
    void invalidSettingsAreRefused() throws Exception {
        tune("concentration", true, "{\"nope\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Unknown parameter")));
        tune("concentration", true, "{\"flagAbove\":-5}").andExpect(status().isBadRequest());
        tune("concentration", true, "{\"flagAbove\":900,\"blockAbove\":100}").andExpect(status().isBadRequest());
        tune("velocity", true, "{\"windowMinutes\":0}").andExpect(status().isBadRequest());
        tune("does-not-exist", true, "{}").andExpect(status().isNotFound());
        mvc.perform(put("/admin/risk-rules/velocity").with(Auth.admin()).contentType(MediaType.APPLICATION_JSON).content("{\"params\":{}}"))
                .andExpect(status().isBadRequest()); // enabled is required
        assertThat(db.queryForObject("SELECT count(*) FROM risk_rule_config", Integer.class)).isZero(); // nothing half-saved
    }

    @Test
    void onlyAdminsMayReadOrChangeRiskSettings() throws Exception {
        for (RequestPostProcessor user : new RequestPostProcessor[] {Auth.analyst(), Auth.issuer(UUID.randomUUID()), Auth.noRoles()}) {
            mvc.perform(get("/admin/risk-rules").with(user)).andExpect(status().isForbidden());
            mvc.perform(put("/admin/risk-rules/velocity").with(user).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":false,\"params\":{}}")).andExpect(status().isForbidden());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM risk_rule_config", Integer.class)).isZero();
    }

    @Test
    void changingARuleIsAudited() throws Exception {
        tune("velocity", true, "{\"flagCount\":7}").andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT details::text FROM audit_log WHERE action = 'RISK_RULE_UPDATED' AND resource = 'risk-rule:velocity'"
                + " ORDER BY at DESC LIMIT 1", String.class)).contains("flagCount");
        db.update("DELETE FROM audit_log WHERE action = 'RISK_RULE_UPDATED' AND resource = 'risk-rule:velocity'");
    }

    // ---------- second-person review of flagged commitments ----------

    @Test
    void anotherPersonApprovesAFlaggedCommitment() throws Exception {
        givenApprovedMatch();
        String id = idOf(commit(1_200_000)); // recorded by the default test admin
        UUID carol = UUID.randomUUID();
        mvc.perform(post("/commitments/" + id + "/approve").with(Auth.as(carol, "ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(db.queryForObject("SELECT reviewed_by FROM commitments WHERE id = ?::uuid", UUID.class, id)).isEqualTo(carol);
        assertThat(remaining()).isEqualByComparingTo("8800000"); // stays held: it is real now
    }

    @Test
    void rejectingAFlaggedCommitmentGivesTheCapacityBack() throws Exception {
        givenApprovedMatch();
        String id = idOf(commit(1_200_000));
        assertThat(remaining()).isEqualByComparingTo("8800000");
        mvc.perform(post("/commitments/" + id + "/reject").with(Auth.analyst()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(remaining()).isEqualByComparingTo("10000000");
    }

    @Test
    void youCannotReviewACommitmentYouRecordedYourself() throws Exception {
        givenApprovedMatch();
        String id = idOf(commit(1_200_000, "risk-" + UUID.randomUUID(), Auth.analyst()));
        mvc.perform(post("/commitments/" + id + "/approve").with(Auth.analyst())).andExpect(status().isForbidden());
        mvc.perform(post("/commitments/" + id + "/reject").with(Auth.analyst())).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT status FROM commitments WHERE id = ?::uuid", String.class, id)).isEqualTo("PENDING_REVIEW");
        assertThat(remaining()).isEqualByComparingTo("8800000");
    }

    @Test
    void reviewIsFinalAndOnlyForFlaggedCommitments() throws Exception {
        givenApprovedMatch();
        String flagged = idOf(commit(1_200_000));
        mvc.perform(post("/commitments/" + flagged + "/approve").with(Auth.analyst())).andExpect(status().isOk());
        mvc.perform(post("/commitments/" + flagged + "/reject").with(Auth.as(UUID.randomUUID(), "ANALYST"))).andExpect(status().isConflict());

        String accepted = idOf(commit(500_000));
        mvc.perform(post("/commitments/" + accepted + "/approve").with(Auth.analyst())).andExpect(status().isConflict());
        String rejected = idOf(commit(2_000_000));
        mvc.perform(post("/commitments/" + rejected + "/approve").with(Auth.analyst())).andExpect(status().isConflict());

        mvc.perform(post("/commitments/" + UUID.randomUUID() + "/approve").with(Auth.analyst())).andExpect(status().isNotFound());
    }

    @Test
    void issuersCannotReviewOrReadCommitments() throws Exception {
        givenApprovedMatch();
        String id = idOf(commit(1_200_000));
        mvc.perform(post("/commitments/" + id + "/approve").with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/commitments/" + id).with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @Test
    void aCommitmentCanBeReadBackWithItsDecisions() throws Exception {
        givenApprovedMatch();
        String id = idOf(commit(1_200_000));
        mvc.perform(get("/commitments/" + id).with(Auth.analyst())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.decisions", hasSize(5)));
        mvc.perform(get("/commitments/" + UUID.randomUUID()).with(Auth.analyst())).andExpect(status().isNotFound());
    }
}
