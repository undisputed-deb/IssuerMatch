package com.issuermatch.security;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** What an INVESTOR may see: its own profile and only the needs a human approved it for. */
@IntegrationTest
@Transactional
class RoleIsolationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    final UUID ivy = UUID.randomUUID();
    final UUID otto = UUID.randomUUID();

    UUID investorOwnedBy(UUID owner) {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region,owner_user_id) VALUES (?,'VC','EUROPE',?) RETURNING id",
                UUID.class, "Iso Inv " + UUID.randomUUID(), owner);
    }

    UUID need() {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Iso " + UUID.randomUUID(), UUID.randomUUID());
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                + " VALUES (?,'t','d','FINTECH','EUROPE',1000,'EQUITY','LOW',1000) RETURNING id", UUID.class, issuer);
    }

    UUID match(UUID need, UUID investor, String runStatus) {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,?) RETURNING id",
                UUID.class, need, UUID.randomUUID(), runStatus);
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,1,90,'ok')", run, investor);
        return run;
    }

    @Test
    void anInvestorSeesOnlyNeedsWithAnApprovedMatchForItsOwnProfile() throws Exception {
        UUID mine = investorOwnedBy(ivy);
        UUID others = investorOwnedBy(otto);
        UUID approved = need();
        UUID pending = need();
        UUID rejected = need();
        UUID forSomeoneElse = need();
        UUID unmatched = need();
        match(approved, mine, "APPROVED");
        match(pending, mine, "PENDING_REVIEW");
        match(rejected, mine, "REJECTED");
        match(forSomeoneElse, others, "APPROVED");

        mvc.perform(get("/funding-needs").with(Auth.investor(ivy))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id").value(approved.toString()));
        mvc.perform(get("/funding-needs/" + approved).with(Auth.investor(ivy))).andExpect(status().isOk());
        for (UUID hidden : new UUID[] {pending, rejected, forSomeoneElse, unmatched}) {
            mvc.perform(get("/funding-needs/" + hidden).with(Auth.investor(ivy))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/funding-needs?issuerId=" + UUID.randomUUID()).with(Auth.investor(ivy)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anInvestorWithNoProfileSeesNothing() throws Exception {
        need();
        mvc.perform(get("/funding-needs").with(Auth.investor(UUID.randomUUID()))).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anInvestorCannotWriteNeedsRunMatchingReviewOrUseStaffTools() throws Exception {
        UUID mine = investorOwnedBy(ivy);
        UUID n = need();
        UUID run = match(n, mine, "APPROVED");
        var investor = Auth.investor(ivy);
        // a VALID body: Spring checks the body before the role, so only a valid one proves the role gate
        UUID issuer = db.queryForObject("SELECT issuer_id FROM funding_needs WHERE id = ?", UUID.class, n);
        mvc.perform(post("/funding-needs").with(investor).contentType(MediaType.APPLICATION_JSON).content("{\"issuerId\":\"" + issuer
                        + "\",\"title\":\"x\",\"description\":\"d\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":5,"
                        + "\"instrument\":\"EQUITY\",\"riskLevel\":\"LOW\"}")).andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT count(*) FROM funding_needs WHERE issuer_id = ?", Integer.class, issuer)).isEqualTo(1);
        mvc.perform(post("/funding-needs/" + n + "/match").with(investor)).andExpect(status().isForbidden()); // even for a matched need
        for (String path : new String[] {"/match-runs", "/match-runs/" + run, "/match-runs/" + run + "/results", "/investors",
                "/investors/" + mine, "/issuers", "/commitments", "/commitments/" + UUID.randomUUID(), "/admin/risk-rules",
                "/admin/dead-letters", "/admin/reconciliation/latest", "/actuator/metrics"}) {
            mvc.perform(get(path).with(investor)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/match-runs/" + run + "/approve").with(investor)).andExpect(status().isForbidden());
        mvc.perform(post("/match-runs/" + run + "/reject").with(investor)).andExpect(status().isForbidden());
    }

    // ---------- own profile ----------

    String profile(String name) {
        return com.issuermatch.support.Profiles.json(name);
    }

    @Test
    void anInvestorCreatesAndUpdatesItsOwnProfileLinkedToItsUserId() throws Exception {
        mvc.perform(get("/investors/me").with(Auth.investor(ivy))).andExpect(status().isNotFound());
        mvc.perform(put("/investors/me").with(Auth.investor(ivy)).contentType(MediaType.APPLICATION_JSON).content(profile("Ivy Fund " + ivy)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ivy Fund " + ivy));
        mvc.perform(put("/investors/me").with(Auth.investor(ivy)).contentType(MediaType.APPLICATION_JSON).content(profile("Ivy Renamed " + ivy)))
                .andExpect(status().isOk());
        mvc.perform(get("/investors/me").with(Auth.investor(ivy))).andExpect(jsonPath("$.name").value("Ivy Renamed " + ivy));
        org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT count(*) FROM investors WHERE owner_user_id = ?", Integer.class, ivy)).isEqualTo(1);
        // someone else sees their own (nothing), never Ivy's
        mvc.perform(get("/investors/me").with(Auth.investor(otto))).andExpect(status().isNotFound());
    }

    @Test
    void onlyInvestorsHaveAnOwnProfileAndTheOwnerCannotBeChosen() throws Exception {
        for (var user : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {Auth.issuer(UUID.randomUUID()), Auth.analyst(), Auth.admin(), Auth.noRoles()}) {
            mvc.perform(get("/investors/me").with(user)).andExpect(status().isForbidden());
        }
        mvc.perform(put("/investors/me").with(Auth.investor(ivy)).contentType(MediaType.APPLICATION_JSON)
                        .content(profile("x").replaceFirst("\\{", "{\"ownerUserId\":\"" + otto + "\",")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issuersStillCannotSeeOtherIssuersNeedsOrInvestors() throws Exception {
        UUID n = need();
        mvc.perform(get("/funding-needs/" + n).with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(get("/investors").with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/commitments").with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @Test
    void staffCanListCommitmentsForRiskReview() throws Exception {
        mvc.perform(get("/commitments?status=PENDING_REVIEW").with(Auth.analyst())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/commitments?status=NONSENSE").with(Auth.analyst())).andExpect(status().isBadRequest());
    }
}
