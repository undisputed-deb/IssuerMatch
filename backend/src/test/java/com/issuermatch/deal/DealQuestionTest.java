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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Private Q&A between one matched investor and the issuer. */
@IntegrationTest
class DealQuestionTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    Deals deal;

    @AfterEach
    void cleanUp() {
        if (deal != null) deal.cleanUp();
    }

    String path() {
        return "/funding-needs/" + deal.needId + "/questions";
    }

    ResultActions ask(RequestPostProcessor user, String question) throws Exception {
        return mvc.perform(post(path()).with(user).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"" + question + "\"}"));
    }

    String idOf(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
    }

    ResultActions answer(RequestPostProcessor user, String questionId, String answer) throws Exception {
        return mvc.perform(post(path() + "/" + questionId + "/answer").with(user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"" + answer + "\"}"));
    }

    @Test
    void anInvestorAsksTheIssuerAnswersAndBothSeeTheThread() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        String q = idOf(ask(Auth.investor(ivy.user()), "What is current revenue?").andExpect(status().isCreated())
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.nullValue())));

        mvc.perform(get(path()).with(Auth.issuer(deal.issuerUser))).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].investorFirm").value(org.hamcrest.Matchers.startsWith("Deal Fund")))
                .andExpect(jsonPath("$[0].question").value("What is current revenue?"));
        answer(Auth.issuer(deal.issuerUser), q, "About 2M a year.").andExpect(status().isOk());
        mvc.perform(get(path()).with(Auth.investor(ivy.user()))).andExpect(jsonPath("$[0].answer").value("About 2M a year."))
                .andExpect(jsonPath("$[0].answeredAt").isNotEmpty());

        // audited with ids only, and mirrored to the audit topic through the outbox
        assertThat(db.queryForList("SELECT action FROM audit_log WHERE resource = ? ORDER BY at", String.class, "deal-question:" + q))
                .containsExactly("DEAL_QUESTION_ASKED", "DEAL_QUESTION_ANSWERED");
        assertThat(db.queryForObject("SELECT string_agg(details::text, ' ') FROM audit_log WHERE resource = ?", String.class, "deal-question:" + q))
                .doesNotContain("revenue").doesNotContain("2M a year");
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE topic = 'audit.events' AND payload LIKE ?", Integer.class, "%" + q + "%"))
                .isEqualTo(2);
    }

    @Test
    void investorsNeverSeeEachOthersThreads() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        Deals.Party otto = deal.investor("APPROVED");
        ask(Auth.investor(ivy.user()), "IVY-ONLY question").andExpect(status().isCreated());
        ask(Auth.investor(otto.user()), "OTTO-ONLY question").andExpect(status().isCreated());

        String ivyView = mvc.perform(get(path()).with(Auth.investor(ivy.user()))).andExpect(jsonPath("$", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        assertThat(ivyView).contains("IVY-ONLY").doesNotContain("OTTO-ONLY");
        mvc.perform(get(path()).with(Auth.issuer(deal.issuerUser))).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(path()).with(Auth.analyst())).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get(path()).with(Auth.admin())).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void outsidersCannotReadAskOrAnswer() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        Deals.Party unmatched = deal.investor(null);
        Deals.Party pending = deal.investor("PENDING_REVIEW");
        String q = idOf(ask(Auth.investor(ivy.user()), "q?"));

        for (RequestPostProcessor outsider : new RequestPostProcessor[] {Auth.investor(unmatched.user()), Auth.investor(pending.user()),
                Auth.investor(UUID.randomUUID()), Auth.issuer(UUID.randomUUID())}) {
            mvc.perform(get(path()).with(outsider)).andExpect(status().isNotFound());
        }
        ask(Auth.investor(unmatched.user()), "let me in").andExpect(status().isNotFound());
        ask(Auth.issuer(deal.issuerUser), "issuers do not ask").andExpect(status().isForbidden());
        ask(Auth.analyst(), "staff do not ask").andExpect(status().isForbidden());
        answer(Auth.investor(ivy.user()), q, "self answer").andExpect(status().isForbidden());
        answer(Auth.analyst(), q, "staff answer").andExpect(status().isForbidden());
        answer(Auth.admin(), q, "admin answer").andExpect(status().isForbidden());
        answer(Auth.issuer(UUID.randomUUID()), q, "wrong issuer").andExpect(status().isNotFound());
        mvc.perform(get(path()).with(Auth.noRoles())).andExpect(status().isForbidden());
    }

    @Test
    void questionsAndAnswersAreValidatedAndAnsweredOnce() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        ask(Auth.investor(ivy.user()), "").andExpect(status().isBadRequest());
        ask(Auth.investor(ivy.user()), "x".repeat(2001)).andExpect(status().isBadRequest());
        String q = idOf(ask(Auth.investor(ivy.user()), "ok?"));
        answer(Auth.issuer(deal.issuerUser), q, " ").andExpect(status().isBadRequest());
        answer(Auth.issuer(deal.issuerUser), q, "x".repeat(4001)).andExpect(status().isBadRequest());
        answer(Auth.issuer(deal.issuerUser), q, "first").andExpect(status().isOk());
        answer(Auth.issuer(deal.issuerUser), q, "second").andExpect(status().isConflict());
        answer(Auth.issuer(deal.issuerUser), UUID.randomUUID().toString(), "none").andExpect(status().isNotFound());
    }

    @Test
    void aQuestionFromAnotherNeedCannotBeAnsweredThroughMyNeed() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals other = new Deals(db, "EQUITY");
        try {
            Deals.Party theirs = other.investor("APPROVED");
            String q = mvc.perform(post("/funding-needs/" + other.needId + "/questions").with(Auth.investor(theirs.user()))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\"}")).andReturn().getResponse().getContentAsString()
                    .replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
            answer(Auth.issuer(deal.issuerUser), q, "hijack").andExpect(status().isNotFound());
        } finally {
            other.cleanUp();
        }
    }

    @Test
    void oneInvestorCannotFloodAThread() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party ivy = deal.investor("APPROVED");
        for (int i = 0; i < 50; i++) {
            db.update("INSERT INTO deal_questions(need_id,investor_id,asked_by,question) VALUES (?,?,?,'q')", deal.needId, ivy.investorId(), ivy.user());
        }
        ask(Auth.investor(ivy.user()), "one too many").andExpect(status().isConflict());
    }
}
