package com.issuermatch.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.Deals;
import com.issuermatch.support.IntegrationTest;
import com.issuermatch.support.Profiles;
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

/** Company and investor profiles, the Verified badge, and what each side may learn about the other. */
@IntegrationTest
class ProfileAlignmentTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    Deals deal;
    final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (deal != null) deal.cleanUp();
        for (UUID owner : owners) {
            db.update("DELETE FROM issuers WHERE owner_user_id = ?", owner);
            db.update("DELETE FROM investors WHERE owner_user_id = ?", owner);
        }
    }

    UUID owner() {
        UUID id = UUID.randomUUID();
        owners.add(id);
        return id;
    }

    static final String PROFILE = "{\"contactName\":\"Ada Park\",\"contactRole\":\"CEO\",\"websiteUrl\":\"https://acme.example.test\","
            + "\"companySummary\":\"Payments for clinics.\",\"foundedYear\":2019,\"teamSizeBand\":\"11-50\","
            + "\"totalRaisedUsd\":2500000,\"runwayMonths\":14,\"dataRoomUrl\":\"https://rooms.example.test/secret-room\"}";

    static String company(String name, String profile) {
        return "{\"name\":\"" + name + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\""
                + (profile == null ? "" : ",\"profile\":" + profile) + "}";
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, RequestPostProcessor user,
                       String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions verify(String kind, UUID id, RequestPostProcessor user, boolean verified) throws Exception {
        return send(patch("/admin/" + kind + "/" + id + "/verify"), user, "{\"verified\":" + verified + "}");
    }

    @Test
    void anIssuerKeepsACompanyProfileAndAnUpdateWithoutOneLeavesItAlone() throws Exception {
        UUID me = owner();
        String name = "Acme " + UUID.randomUUID();
        String created = send(post("/issuers"), Auth.issuer(me), company(name, PROFILE)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.verified").value(false)).andExpect(jsonPath("$.profile.teamSizeBand").value("11-50"))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");

        send(put("/issuers/" + id), Auth.issuer(me), company(name, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.runwayMonths").value(14));
        send(put("/issuers/" + id), Auth.issuer(me), company(name, "{\"contactName\":\"Bo\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.contactName").value("Bo"))
                .andExpect(jsonPath("$.profile.runwayMonths").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void unsafeOrImpossibleProfileValuesAreRefusedAndNobodyCanVerifyThemselves() throws Exception {
        UUID me = owner();
        for (String bad : new String[] {"{\"websiteUrl\":\"javascript:alert(1)\"}", "{\"dataRoomUrl\":\"data:text/html,x\"}",
                "{\"foundedYear\":2090}", "{\"foundedYear\":1500}", "{\"teamSizeBand\":\"lots\"}", "{\"runwayMonths\":-1}",
                "{\"totalRaisedUsd\":-1}", "{\"companySummary\":\"" + "x".repeat(2001) + "\"}", "{\"verified\":true}"}) {
            send(post("/issuers"), Auth.issuer(me), company("Bad " + UUID.randomUUID(), bad)).andExpect(status().isBadRequest());
        }
        send(post("/issuers"), Auth.issuer(me), "{\"name\":\"V " + UUID.randomUUID()
                + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"verified\":true}").andExpect(status().isBadRequest());
        send(put("/investors/me"), Auth.investor(owner()), Profiles.json("Self " + UUID.randomUUID())
                .replaceFirst("\\{", "{\"verified\":true,")).andExpect(status().isBadRequest());
    }

    @Test
    void onlyAnAdminGrantsOrRemovesTheVerifiedBadgeAndItIsAudited() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");

        for (var target : new Object[][] {{"issuers", deal.issuerId}, {"investors", ivy.investorId()}}) {
            String kind = (String) target[0];
            UUID id = (UUID) target[1];
            for (RequestPostProcessor other : new RequestPostProcessor[] {Auth.analyst(), Auth.issuer(deal.issuerUser),
                    Auth.investor(ivy.user())}) {
                verify(kind, id, other, true).andExpect(status().isForbidden());
            }
            verify(kind, id, Auth.anonymous(), true).andExpect(status().isUnauthorized());
            verify(kind, UUID.randomUUID(), Auth.admin(), true).andExpect(status().isNotFound());
            send(patch("/admin/" + kind + "/" + id + "/verify"), Auth.admin(), "{}").andExpect(status().isBadRequest());

            verify(kind, id, Auth.admin(), true).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
            assertThat(db.queryForObject("SELECT verified FROM " + kind + " WHERE id = ?", Boolean.class, id)).isTrue();
        }
        // an owner saving the company afterwards cannot overwrite the flag
        String name = db.queryForObject("SELECT name FROM issuers WHERE id = ?", String.class, deal.issuerId);
        send(put("/issuers/" + deal.issuerId), Auth.issuer(deal.issuerUser), company(name, PROFILE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        verify("issuers", deal.issuerId, Auth.admin(), false).andExpect(status().isOk());
        assertThat(db.queryForList("SELECT action FROM audit_log WHERE resource = ? AND action LIKE '%VERIFIED' ORDER BY at",
                String.class, "issuer:" + deal.issuerId)).containsExactly("ISSUER_VERIFIED", "ISSUER_UNVERIFIED");
        assertThat(db.queryForList("SELECT action FROM audit_log WHERE resource = ?", String.class, "investor:" + ivy.investorId()))
                .containsExactly("INVESTOR_VERIFIED");
        mvc.perform(options("/admin/issuers/" + deal.issuerId + "/verify").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH").with(Auth.anonymous())).andExpect(status().isOk());
    }

    @Test
    void aMatchedInvestorSeesTheCompanyCardButNeitherRunwayNorDataRoom() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        String name = db.queryForObject("SELECT name FROM issuers WHERE id = ?", String.class, deal.issuerId);
        send(put("/issuers/" + deal.issuerId), Auth.issuer(deal.issuerUser), company(name, PROFILE)).andExpect(status().isOk());
        verify("issuers", deal.issuerId, Auth.admin(), true).andExpect(status().isOk());

        String feed = mvc.perform(get("/investor/matches").with(Auth.investor(ivy.user()))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company.verified").value(true))
                .andExpect(jsonPath("$[0].company.contactName").value("Ada Park"))
                .andExpect(jsonPath("$[0].company.websiteUrl").value("https://acme.example.test"))
                .andExpect(jsonPath("$[0].company.summary").value("Payments for clinics."))
                .andExpect(jsonPath("$[0].company.foundedYear").value(2019))
                .andReturn().getResponse().getContentAsString();
        assertThat(feed).doesNotContain("runway").doesNotContain("dataRoom").doesNotContain("secret-room");
    }

    @Test
    void theOwningIssuerSeesASummaryOfTheFirmThatOffers() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        db.update("UPDATE investors SET aum_or_revenue_usd = 250000000, deployable_capital_usd = 40000000,"
                + " website_url = 'https://fund.example.test', long_thesis_description = 'private thesis words' WHERE id = ?",
                ivy.investorId());
        verify("investors", ivy.investorId(), Auth.admin(), true).andExpect(status().isOk());
        db.update("INSERT INTO commitments(need_id,investor_id,amount,status) VALUES (?,?,100000,'ACCEPTED')", deal.needId,
                ivy.investorId());

        String offers = mvc.perform(get("/funding-needs/" + deal.needId + "/offers").with(Auth.issuer(deal.issuerUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contactName").value("Ivy"))
                .andExpect(jsonPath("$[0].investorType").value("VC"))
                .andExpect(jsonPath("$[0].aumOrRevenueUsd").value(250000000))
                .andExpect(jsonPath("$[0].deployableCapitalUsd").value(40000000))
                .andExpect(jsonPath("$[0].websiteUrl").value("https://fund.example.test"))
                .andExpect(jsonPath("$[0].verified").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(offers).doesNotContain("private thesis words");
        mvc.perform(get("/funding-needs/" + deal.needId + "/offers").with(Auth.issuer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvestorSetsRoleStagesAndDeploymentAndAnOmittedStatusStaysPaused() throws Exception {
        UUID me = owner();
        String firm = "Align " + UUID.randomUUID();
        String paused = Profiles.json(firm).replaceFirst("\\{", "{\"participationRole\":\"LEAD\",\"deploymentStatus\":\"PAUSED\",")
                .replace("\"instruments\":[\"EQUITY\"]", "\"instruments\":[\"EQUITY\"],\"targetStages\":[\"SEED\",\"SERIES_A\",\"SEED\"]");
        send(put("/investors/me"), Auth.investor(me), paused).andExpect(status().isOk());
        mvc.perform(get("/investors/me").with(Auth.investor(me))).andExpect(jsonPath("$.participationRole").value("LEAD"))
                .andExpect(jsonPath("$.deploymentStatus").value("PAUSED")).andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.mandates[0].targetStages", org.hamcrest.Matchers.contains("SEED", "SERIES_A")));

        send(put("/investors/me"), Auth.investor(me), Profiles.json(firm)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentStatus").value("PAUSED")); // not sent, so not changed
        for (String bad : new String[] {"\"targetStages\":[\"IPO\"]", "\"targetStages\":[null]"}) {
            send(put("/investors/me"), Auth.investor(me), Profiles.json(firm)
                    .replace("\"instruments\":[\"EQUITY\"]", "\"instruments\":[\"EQUITY\"]," + bad)).andExpect(status().isBadRequest());
        }
        send(put("/investors/me"), Auth.investor(me), Profiles.json(firm).replaceFirst("\\{", "{\"deploymentStatus\":\"SLEEPING\","))
                .andExpect(status().isBadRequest());
    }
}
