package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** Who may do what. The IDOR cases (user A touching user B's data) are the important ones. */
@IntegrationTest
@Transactional
class AuthorizationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    UUID issuerOf(UUID owner) {
        return db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Authz " + UUID.randomUUID(), owner);
    }

    UUID needOf(UUID issuer) {
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1000,'EQUITY','LOW',1000) RETURNING id",
                UUID.class, issuer);
    }

    UUID investor() {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Authz Inv " + UUID.randomUUID());
    }

    ResultActions call(MockHttpServletRequestBuilder req, RequestPostProcessor user, String json) throws Exception {
        return mvc.perform(req.with(user).contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    String issuerBody(String name, UUID owner) {
        return "{\"name\":\"" + name + "\"," + (owner == null ? "" : "\"ownerUserId\":\"" + owner + "\",")
                + "\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}";
    }

    String needBody(UUID issuer) {
        return "{\"issuerId\":\"" + issuer + "\",\"title\":\"t\",\"description\":\"d\",\"sector\":\"FINTECH\","
                + "\"region\":\"EUROPE\",\"amount\":1000,\"instrument\":\"EQUITY\",\"riskLevel\":\"LOW\"}";
    }

    // ---------- IDOR: issuers ----------

    @Test
    void anIssuerCannotReadChangeOrDeleteAnotherIssuersCompany() throws Exception {
        UUID bobs = issuerOf(bob);
        call(get("/issuers/" + bobs), Auth.issuer(alice), null).andExpect(status().isNotFound()); // 404, not 403: no hint it exists
        call(put("/issuers/" + bobs), Auth.issuer(alice), "{\"name\":\"Hacked\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}")
                .andExpect(status().isNotFound());
        call(delete("/issuers/" + bobs), Auth.issuer(alice), null).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT name FROM issuers WHERE id=?", String.class, bobs)).startsWith("Authz");
    }

    @Test
    void anIssuerListSeesOnlyItsOwnCompanies() throws Exception {
        UUID mine = issuerOf(alice);
        issuerOf(bob);
        call(get("/issuers"), Auth.issuer(alice), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine.toString()));
    }

    @Test
    void anIssuerCannotCreateACompanyOwnedBySomeoneElse() throws Exception {
        call(post("/issuers"), Auth.issuer(alice), issuerBody("Sneaky", bob)).andExpect(status().isForbidden());
    }

    @Test
    void anIssuerCreatesCompaniesOwnedByItself() throws Exception {
        call(post("/issuers"), Auth.issuer(alice), issuerBody("Mine " + UUID.randomUUID(), null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerUserId").value(alice.toString()));
    }

    @Test
    void anAdminMayCreateACompanyForAnOwner() throws Exception {
        call(post("/issuers"), Auth.admin(), issuerBody("Admin made " + UUID.randomUUID(), bob))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerUserId").value(bob.toString()));
    }

    @Test
    void anOwnerCanChangeAndDeleteItsOwnCompany() throws Exception {
        UUID mine = issuerOf(alice);
        call(put("/issuers/" + mine), Auth.issuer(alice), "{\"name\":\"Renamed\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}")
                .andExpect(status().isOk());
        call(delete("/issuers/" + mine), Auth.issuer(alice), null).andExpect(status().isNoContent());
    }

    @Test
    void analystsReadEverythingButChangeNothing() throws Exception {
        UUID bobs = issuerOf(bob);
        call(get("/issuers/" + bobs), Auth.analyst(), null).andExpect(status().isOk());
        call(get("/issuers"), Auth.analyst(), null).andExpect(status().isOk());
        call(put("/issuers/" + bobs), Auth.analyst(), "{\"name\":\"x\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}").andExpect(status().isForbidden());
        call(delete("/issuers/" + bobs), Auth.analyst(), null).andExpect(status().isForbidden());
        call(post("/issuers"), Auth.analyst(), issuerBody("Nope", null)).andExpect(status().isForbidden());
    }

    // ---------- IDOR: funding needs ----------

    @Test
    void anIssuerCannotReadAnotherIssuersFundingNeed() throws Exception {
        UUID bobsNeed = needOf(issuerOf(bob));
        call(get("/funding-needs/" + bobsNeed), Auth.issuer(alice), null).andExpect(status().isNotFound());
    }

    @Test
    void anIssuerCannotSubmitANeedForSomeoneElsesCompany() throws Exception {
        UUID bobs = issuerOf(bob);
        call(post("/funding-needs"), Auth.issuer(alice), needBody(bobs)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT count(*) FROM funding_needs WHERE issuer_id=?", Integer.class, bobs)).isZero();
    }

    @Test
    void anIssuersNeedListHidesOtherIssuersEvenWhenFilteredByTheirId() throws Exception {
        UUID aliceNeed = needOf(issuerOf(alice));
        UUID bobs = issuerOf(bob);
        needOf(bobs);
        call(get("/funding-needs"), Auth.issuer(alice), null)
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id").value(aliceNeed.toString()));
        call(get("/funding-needs?issuerId=" + bobs), Auth.issuer(alice), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anIssuerSubmitsNeedsForItsOwnCompanyAndReadsThemBack() throws Exception {
        UUID mine = issuerOf(alice);
        String res = call(post("/funding-needs"), Auth.issuer(alice), needBody(mine)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        call(get("/funding-needs/" + id), Auth.issuer(alice), null).andExpect(status().isOk());
    }

    @Test
    void analystsReadAllNeedsButCannotSubmitThem() throws Exception {
        UUID bobsNeed = needOf(issuerOf(bob));
        call(get("/funding-needs/" + bobsNeed), Auth.analyst(), null).andExpect(status().isOk());
        call(post("/funding-needs"), Auth.analyst(), needBody(issuerOf(bob))).andExpect(status().isForbidden());
    }

    // ---------- investors are confidential to staff ----------

    @Test
    void issuersAndRolelessUsersCannotSeeInvestors() throws Exception {
        UUID inv = investor();
        for (RequestPostProcessor user : new RequestPostProcessor[] {Auth.issuer(alice), Auth.noRoles()}) {
            call(get("/investors"), user, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
            call(get("/investors/" + inv), user, null).andExpect(status().isForbidden());
            call(get("/investors/" + inv + "/mandates"), user, null).andExpect(status().isForbidden());
        }
    }

    @Test
    void analystsReadInvestorsButOnlyAdminsChangeThem() throws Exception {
        UUID inv = investor();
        call(get("/investors/" + inv), Auth.analyst(), null).andExpect(status().isOk());
        String body = "{\"name\":\"X " + UUID.randomUUID() + "\",\"investorType\":\"VC\",\"region\":\"EUROPE\"}";
        call(post("/investors"), Auth.analyst(), body).andExpect(status().isForbidden());
        call(put("/investors/" + inv), Auth.analyst(), body).andExpect(status().isForbidden());
        call(delete("/investors/" + inv), Auth.analyst(), null).andExpect(status().isForbidden());
        call(post("/investors/" + inv + "/mandates"), Auth.analyst(),
                "{\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"minTicket\":1,\"maxTicket\":2,\"riskLevel\":\"LOW\",\"instruments\":[\"EQUITY\"]}")
                .andExpect(status().isForbidden());
        call(post("/investors"), Auth.admin(), body).andExpect(status().isCreated());
    }

    // ---------- matching and commitments are staff-only ----------

    @Test
    void issuersCannotReadOrReviewMatchRunsOrCommit() throws Exception {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'PENDING_REVIEW') RETURNING id",
                UUID.class, needOf(issuerOf(alice)), UUID.randomUUID()); // even for their OWN need
        call(get("/match-runs"), Auth.issuer(alice), null).andExpect(status().isForbidden());
        call(get("/match-runs/" + run), Auth.issuer(alice), null).andExpect(status().isForbidden());
        call(get("/match-runs/" + run + "/results"), Auth.issuer(alice), null).andExpect(status().isForbidden());
        call(post("/match-runs/" + run + "/approve"), Auth.issuer(alice), null).andExpect(status().isForbidden());
        call(post("/match-runs/" + run + "/reject"), Auth.issuer(alice), null).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT status FROM match_runs WHERE id=?", String.class, run)).isEqualTo("PENDING_REVIEW");
        call(post("/commitments").header("Idempotency-Key", "k-" + UUID.randomUUID()), Auth.issuer(alice),
                "{\"needId\":\"" + UUID.randomUUID() + "\",\"investorId\":\"" + UUID.randomUUID() + "\",\"amount\":5}")
                .andExpect(status().isForbidden());
    }

    @Test
    void theReviewerIsRecordedFromTheTokenNotFromTheRequest() throws Exception {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'PENDING_REVIEW') RETURNING id",
                UUID.class, needOf(issuerOf(alice)), UUID.randomUUID());
        UUID carol = UUID.randomUUID();
        call(post("/match-runs/" + run + "/approve"), Auth.as(carol, "ANALYST"), null).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT reviewed_by FROM match_runs WHERE id=?", UUID.class, run)).isEqualTo(carol);
    }
}
