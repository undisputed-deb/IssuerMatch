package com.issuermatch.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.matching.agent.Answers;
import com.issuermatch.matching.agent.FakeLlmClient;
import com.issuermatch.matching.agent.LlmResult;
import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import com.issuermatch.support.Profiles;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two-sided product through its public API only, with real Postgres and Kafka and a scripted model:
 * profile, need, agent match, approval, feed, question, answer, offer. Then outsiders try to get at that same deal.
 */
@IntegrationTest
class TwoSidedPlatformFlowTest {

    private static final long TICKET = 3_141_593; // the raise size; no other test uses it, so the scripted model answers only this need

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired JsonMapper json;
    @Autowired FakeLlmClient fake;

    final UUID investorUser = UUID.randomUUID();
    final UUID issuerUser = UUID.randomUUID();
    final UUID rivalInvestorUser = UUID.randomUUID();
    final UUID rivalIssuerUser = UUID.randomUUID();
    String needId;
    String commitmentId;

    @AfterEach
    void cleanUp() {
        if (needId != null) {
            db.update("DELETE FROM idempotency_keys WHERE actor = ?", investorUser);
            db.update("DELETE FROM commitment_events WHERE need_id = ?::uuid", needId);
            db.update("DELETE FROM commitments WHERE need_id = ?::uuid", needId);
        }
        db.update("DELETE FROM issuers WHERE owner_user_id IN (?, ?)", issuerUser, rivalIssuerUser);
        db.update("DELETE FROM investors WHERE owner_user_id IN (?, ?)", investorUser, rivalInvestorUser);
    }

    JsonNode call(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body, int expected) throws Exception {
        request.with(user);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String out = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isEmpty() ? null : json.readTree(out);
    }

    String profile(String name) {
        return Profiles.json(name + " " + UUID.randomUUID(), "We back European fintech with recurring revenue.",
                Profiles.mandate("FINTECH", "EUROPE", 1_000_000, TICKET)).replace("\"currency\":\"EUR\"", "\"currency\":\"USD\"");
    }

    @Test
    void theWholeDealFromProfileToOfferAndNobodyElseCanSeeIt() throws Exception {
        RequestPostProcessor investor = Auth.investor(investorUser);
        RequestPostProcessor issuer = Auth.issuer(issuerUser);

        // 1. the investor creates a profile
        String investorId = call(put("/investors/me"), investor, profile("Flow Fund"), 200).get("id").asString();

        // 2. the issuer creates a company and submits a need with dynamic terms and a deal breaker
        String issuerId = call(post("/issuers"), issuer,
                "{\"name\":\"Flow Co " + UUID.randomUUID() + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}", 201).get("id").asString();

        // 3. the internal agent matches (scripted model calling the real tools)
        fake.when(p -> p.contains("amount=" + TICKET), (system, user, tools) -> {
            Matcher m = Pattern.compile("id=([0-9a-f-]{36})").matcher(user);
            assertThat(m.find()).isTrue();
            FakeLlmClient.callTool(tools, "searchInvestorsByMandate",
                    "{\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ticketSizeUsd\":" + TICKET + "}");
            FakeLlmClient.callTool(tools, "scoreFit", "{\"needId\":\"" + m.group(1) + "\",\"investorIds\":[\"" + investorId + "\"]}");
            return new LlmResult(Answers.rankings(
                    Answers.entry(investorId, "Exact fit.", "[\"searchInvestorsByMandate\",\"scoreFit\"]")), 100, 50);
        });
        needId = call(post("/funding-needs"), issuer, "{\"issuerId\":\"" + issuerId + "\",\"title\":\"Flow raise\","
                + "\"description\":\"Payments expansion\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":" + TICKET
                + ",\"instrument\":\"EQUITY\",\"riskLevel\":\"MEDIUM\",\"currency\":\"USD\",\"companyStage\":\"SERIES_A\","
                + "\"useOfFunds\":[\"Hiring\"],\"hardConstraints\":{\"allowedInvestorRegions\":[\"EUROPE\"]},"
                + "\"instrumentTerms\":{\"type\":\"EQUITY\",\"targetValuation\":30000000}}", 202).get("id").asString();
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM match_runs WHERE need_id = ?::uuid AND status = 'PENDING_REVIEW'", Integer.class, needId) == 1);
        String runId = db.queryForObject("SELECT id::text FROM match_runs WHERE need_id = ?::uuid", String.class, needId);

        // nothing reaches the investor before a human approves, and the investor cannot approve
        assertThat(call(get("/investor/matches"), investor, null, 200)).isEmpty();
        call(get("/funding-needs/" + needId), investor, null, 404);
        call(post("/match-runs/" + runId + "/approve"), investor, null, 403);
        call(post("/match-runs/" + runId + "/approve"), issuer, null, 403);

        // 4. an analyst approves
        call(post("/match-runs/" + runId + "/approve"), Auth.analyst(), null, 200);

        // 5. the investor sees the deal in its feed, with the score breakdown, brief and questions
        JsonNode deal = call(get("/investor/matches"), investor, null, 200).get(0);
        assertThat(deal.get("needId").asString()).isEqualTo(needId);
        assertThat(deal.get("scoreBreakdown").get("mandateFit").asDouble()).isEqualTo(25.0);
        assertThat(deal.get("dealBrief").asString()).isNotBlank();
        assertThat(deal.has("explanation")).isFalse();
        String suggested = deal.get("suggestedDiligenceQuestions").get(0).asString();

        // 6. one-click question, 7. the issuer replies
        String questionId = call(post("/funding-needs/" + needId + "/questions"), investor,
                "{\"question\":\"" + suggested + "\"}", 201).get("id").asString();
        assertThat(call(get("/funding-needs/" + needId + "/questions"), issuer, null, 200).get(0).get("question").asString())
                .isEqualTo(suggested);
        call(post("/funding-needs/" + needId + "/questions/" + questionId + "/answer"), issuer, "{\"answer\":\"2M a year.\"}", 200);
        assertThat(call(get("/funding-needs/" + needId + "/questions"), investor, null, 200).get(0).get("answer").asString())
                .isEqualTo("2M a year.");

        // 8. an idempotent, risk-checked offer with stake and rationale
        String offer = "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\",\"amount\":2000000"
                + ",\"proposedStakePct\":10,\"proposedTerms\":{\"valuation\":30000000},\"investmentRationale\":\"Strong traction.\"}";
        String key = "flow-" + UUID.randomUUID();
        commitmentId = call(post("/commitments").header("Idempotency-Key", key), investor, offer, 201).get("id").asString();
        mvc.perform(post("/commitments").with(investor).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(offer)).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(commitmentId));
        assertThat(db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ?::uuid", Integer.class, needId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM risk_decisions WHERE commitment_id = ?::uuid", Integer.class, commitmentId))
                .isEqualTo(5);

        // 9. the issuer sees the offer
        mvc.perform(get("/funding-needs/" + needId + "/offers").with(issuer)).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].commitmentId").value(commitmentId))
                .andExpect(jsonPath("$[0].proposedStakePct").value(10.0))
                .andExpect(jsonPath("$[0].investmentRationale").value("Strong traction."));
        // the events travelled through Kafka into the ledger reconciliation compares against
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM commitment_events WHERE commitment_id = ?::uuid", Integer.class, commitmentId) == 2);

        outsidersGetNothing(runId, questionId, investorId, offer);
    }

    /** Another investor (with a real profile) and another issuer (with a real company) attack the finished deal. */
    private void outsidersGetNothing(String runId, String questionId, String investorId, String offer) throws Exception {
        RequestPostProcessor rival = Auth.investor(rivalInvestorUser);
        String rivalId = call(put("/investors/me"), rival, profile("Rival Fund"), 200).get("id").asString();
        assertThat(call(get("/investor/matches"), rival, null, 200)).isEmpty();
        assertThat(call(get("/investor/offers"), rival, null, 200)).isEmpty();
        assertThat(call(get("/funding-needs"), rival, null, 200).get("total").asInt()).isZero();
        call(get("/funding-needs/" + needId), rival, null, 404);
        call(get("/funding-needs/" + needId + "/questions"), rival, null, 404);
        call(post("/funding-needs/" + needId + "/questions"), rival, "{\"question\":\"q\"}", 404);
        call(get("/funding-needs/" + needId + "/offers"), rival, null, 403);
        call(get("/commitments/" + commitmentId), rival, null, 403);
        call(post("/match-runs/" + runId + "/approve"), rival, null, 403);
        call(post("/commitments").header("Idempotency-Key", "flow-" + UUID.randomUUID()), rival, offer, 404); // posing as the investor
        call(post("/commitments").header("Idempotency-Key", "flow-" + UUID.randomUUID()), rival,
                offer.replace(investorId, rivalId), 404); // as itself, on a deal it was never matched to

        RequestPostProcessor rivalIssuer = Auth.issuer(rivalIssuerUser);
        call(post("/issuers"), rivalIssuer, "{\"name\":\"Rival Co " + UUID.randomUUID() + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\"}", 201);
        assertThat(call(get("/funding-needs"), rivalIssuer, null, 200).get("total").asInt()).isZero();
        call(get("/funding-needs/" + needId), rivalIssuer, null, 404);
        call(get("/funding-needs/" + needId + "/offers"), rivalIssuer, null, 404);
        call(get("/funding-needs/" + needId + "/questions"), rivalIssuer, null, 404);
        call(post("/funding-needs/" + needId + "/questions/" + questionId + "/answer"), rivalIssuer, "{\"answer\":\"x\"}", 404);
        // internal investor lists stay closed to every issuer, the deal's own included
        for (RequestPostProcessor anyIssuer : new RequestPostProcessor[] {rivalIssuer, Auth.issuer(issuerUser)}) {
            for (String path : new String[] {"/investors", "/investors/" + investorId, "/match-runs", "/match-runs/" + runId,
                    "/match-runs/" + runId + "/results", "/commitments", "/investor/matches"}) {
                call(get(path), anyIssuer, null, 403);
            }
        }
    }
}
