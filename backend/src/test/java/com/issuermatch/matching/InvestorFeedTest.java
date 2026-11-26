package com.issuermatch.matching;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class InvestorFeedTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    final UUID ivy = UUID.randomUUID();
    final UUID otto = UUID.randomUUID();

    UUID investor(UUID owner) {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region,owner_user_id) VALUES (?,'VC','EUROPE',?) RETURNING id",
                UUID.class, "Feed " + UUID.randomUUID(), owner);
    }

    UUID need(String title) {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "FeedCo " + UUID.randomUUID(), UUID.randomUUID());
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,currency,instrument,risk_level,"
                + "remaining_capacity,use_of_funds) VALUES (?,?,'d','FINTECH','EUROPE',1000,'GBP','EQUITY','LOW',1000,'[\"hiring\"]') RETURNING id",
                UUID.class, issuer, title);
    }

    void result(UUID need, UUID investor, String status, String brief) {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status,reviewed_at) VALUES (?,?,?,now()) RETURNING id",
                UUID.class, need, UUID.randomUUID(), status);
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation,score_breakdown,deal_brief,suggested_diligence_questions)"
                + " VALUES (?,?,1,88,'ANALYST-ONLY text','{\"ticketFit\":25}',?,'[\"q1\",\"q2\",\"q3\"]')", run, investor, brief);
    }

    @Test
    void showsOnlyApprovedDealsForTheCallersOwnProfileWithoutAnalystText() throws Exception {
        UUID mine = investor(ivy);
        UUID theirs = investor(otto);
        UUID approved = need("Approved deal");
        result(approved, mine, "APPROVED", "brief for ivy");
        result(approved, theirs, "REJECTED", "x");
        result(need("Pending"), mine, "PENDING_REVIEW", "x");
        result(need("Rejected"), mine, "REJECTED", "x");
        result(need("Not mine"), theirs, "APPROVED", "brief for otto");

        String body = mvc.perform(get("/investor/matches").with(Auth.investor(ivy))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].needId").value(approved.toString()))
                .andExpect(jsonPath("$[0].dealBrief").value("brief for ivy"))
                .andExpect(jsonPath("$[0].suggestedDiligenceQuestions", hasSize(3)))
                .andExpect(jsonPath("$[0].scoreBreakdown.ticketFit").value(25))
                .andExpect(jsonPath("$[0].currency").value("GBP"))
                .andExpect(jsonPath("$[0].useOfFunds[0]").value("hiring"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("ANALYST-ONLY").doesNotContain("explanation")
                .doesNotContain("rank").doesNotContain("brief for otto");
    }

    @Test
    void oneEntryPerNeedUsingTheLatestApprovedRun() throws Exception {
        UUID mine = investor(ivy);
        UUID n = need("Twice");
        result(n, mine, "APPROVED", "older");
        db.update("UPDATE match_runs SET reviewed_at = now() - interval '1 day' WHERE need_id = ?", n);
        result(n, mine, "APPROVED", "newer");
        mvc.perform(get("/investor/matches").with(Auth.investor(ivy)))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].dealBrief").value("newer"));
    }

    @Test
    void onlyInvestorsHaveAFeed() throws Exception {
        for (var user : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {Auth.issuer(ivy), Auth.analyst(), Auth.admin(), Auth.noRoles()}) {
            mvc.perform(get("/investor/matches").with(user)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/investor/matches").with(Auth.anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/investor/matches").with(Auth.investor(UUID.randomUUID()))).andExpect(jsonPath("$", hasSize(0)));
    }
}
