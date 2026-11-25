package com.issuermatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.matching.agent.FakeLlmClient;
import com.issuermatch.matching.agent.LlmResult;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole path with real Postgres and Kafka: POST a need -> outbox -> Kafka -> match run -> match.requested -> agent
 * (scripted fake model) -> PENDING_REVIEW -> human approves. Committed rows, cleaned up afterwards.
 */
@IntegrationTest
class MatchFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired FakeLlmClient fake;

    UUID issuerId;
    UUID strong;
    UUID weak;

    @AfterEach
    void cleanUp() {
        if (issuerId != null) db.update("DELETE FROM issuers WHERE id = ?", issuerId); // cascades needs, runs, results
        if (strong != null) db.update("DELETE FROM investors WHERE id = ?", strong);
        if (weak != null) db.update("DELETE FROM investors WHERE id = ?", weak);
    }

    /** Both investors fit the exact ticket. strong scores 100, weak scores 85 (risk one step off, no EQUITY). */
    void givenIssuerAndTwoInvestors(long ticket) {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Flow " + UUID.randomUUID(), UUID.randomUUID());
        strong = investor(ticket, "MEDIUM", "EQUITY");
        weak = investor(ticket, "LOW", "DEBT");
    }

    UUID investor(long ticket, String risk, String instrument) {
        UUID id = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Flow Inv " + UUID.randomUUID());
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',?,?,?,?::text[])", id, ticket, ticket, risk, "{" + instrument + "}");
        return id;
    }

    String submit(long ticket) throws Exception {
        String res = mvc.perform(post("/funding-needs").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"issuerId\":\"" + issuerId + "\",\"title\":\"Flow\",\"description\":\"Expansion capital\","
                                + "\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":" + ticket
                                + ",\"instrument\":\"EQUITY\",\"riskLevel\":\"MEDIUM\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    /** What a good model does, listing the WEAK investor first to prove the order is not trusted. */
    LlmResult goodAnswer(String userPrompt, List<ToolCallback> t, long ticket) {
        Matcher m = Pattern.compile("id=([0-9a-f-]{36})").matcher(userPrompt);
        assertThat(m.find()).isTrue();
        String needId = m.group(1);
        FakeLlmClient.callTool(t, "searchInvestorsByMandate", "{\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ticketSize\":" + ticket + "}");
        for (UUID inv : List.of(weak, strong)) {
            FakeLlmClient.callTool(t, "scoreFit", "{\"needId\":\"" + needId + "\",\"investorId\":\"" + inv + "\"}");
        }
        FakeLlmClient.callTool(t, "getMarketContext", "{\"sector\":\"FINTECH\"}");
        String entry = "{\"investorId\":\"%s\",\"explanation\":\"%s\",\"citedTools\":[\"scoreFit\",\"searchInvestorsByMandate\"]}";
        return new LlmResult("{\"rankings\":[" + entry.formatted(weak, "Risk is one step off.") + ","
                + entry.formatted(strong, "Exact sector, region, ticket and risk fit.") + "]}", 480, 120);
    }

    String awaitRun(String needId, String status) {
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM match_runs WHERE need_id = ?::uuid AND status = ?", Integer.class, needId, status) == 1);
        return db.queryForObject("SELECT id::text FROM match_runs WHERE need_id = ?::uuid", String.class, needId);
    }

    @Test
    void needFlowsToPendingReviewThenAHumanApproves() throws Exception {
        long ticket = 7_654_321;
        givenIssuerAndTwoInvestors(ticket);
        fake.when(p -> p.contains("amount=" + ticket), (s, u, t) -> goodAnswer(u, t, ticket));

        String needId = submit(ticket);
        String runId = awaitRun(needId, "PENDING_REVIEW");
        assertThat(db.queryForObject("SELECT requested_by::text FROM match_runs WHERE id = ?::uuid", String.class, runId))
                .isEqualTo("00000000-0000-0000-0000-0000000000ad"); // the (default test) admin who submitted the need

        // ranked by the Java score, not by the order the model listed them
        mvc.perform(get("/match-runs/" + runId + "/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].investorId").value(strong.toString()))
                .andExpect(jsonPath("$[0].score").value(100.0))
                .andExpect(jsonPath("$[0].citedTools[0]").value("scoreFit"))
                .andExpect(jsonPath("$[1].investorId").value(weak.toString()))
                .andExpect(jsonPath("$[1].score").value(85.0));

        // explainability: every tool call is on the run, with usage and latency
        mvc.perform(get("/match-runs/" + runId))
                .andExpect(jsonPath("$.run.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.run.tokensIn").value(480))
                .andExpect(jsonPath("$.run.tokensOut").value(120))
                .andExpect(jsonPath("$.toolCalls.length()").value(4))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("searchInvestorsByMandate"));

        // the agent cannot approve; the need is not matched until a human says so
        assertThat(db.queryForObject("SELECT status FROM funding_needs WHERE id = ?::uuid", String.class, needId)).isEqualTo("MATCHING");

        mvc.perform(post("/match-runs/" + runId + "/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedAt").exists());
        assertThat(db.queryForObject("SELECT status FROM funding_needs WHERE id = ?::uuid", String.class, needId)).isEqualTo("MATCHED");

        // reviewed once, final
        mvc.perform(post("/match-runs/" + runId + "/approve")).andExpect(status().isConflict());
        mvc.perform(post("/match-runs/" + runId + "/reject")).andExpect(status().isConflict());

        // the completion event was published
        await().atMost(Duration.ofSeconds(10)).until(() -> db.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic='match.completed' AND event_key=? AND published_at IS NOT NULL",
                Integer.class, needId) == 1);
    }

    @Test
    void rejectingSendsTheNeedBackToSubmitted() throws Exception {
        long ticket = 7_654_322;
        givenIssuerAndTwoInvestors(ticket);
        fake.when(p -> p.contains("amount=" + ticket), (s, u, t) -> goodAnswer(u, t, ticket));

        String needId = submit(ticket);
        String runId = awaitRun(needId, "PENDING_REVIEW");

        mvc.perform(post("/match-runs/" + runId + "/reject"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(db.queryForObject("SELECT status FROM funding_needs WHERE id = ?::uuid", String.class, needId)).isEqualTo("SUBMITTED");
    }

    @Test
    void aModelThatKeepsFailingValidationEndsAsAFailedRunWithNoResults() throws Exception {
        long ticket = 7_654_323;
        givenIssuerAndTwoInvestors(ticket);
        fake.when(p -> p.contains("amount=" + ticket), (s, u, t) -> new LlmResult("I refuse to answer in JSON.", 5, 5));

        String needId = submit(ticket);
        String runId = awaitRun(needId, "FAILED");

        mvc.perform(get("/match-runs/" + runId))
                .andExpect(jsonPath("$.run.status").value("FAILED"))
                .andExpect(jsonPath("$.run.error").value("Model output invalid after one retry"));
        mvc.perform(get("/match-runs/" + runId + "/results")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/match-runs/" + runId + "/approve")).andExpect(status().isConflict()); // nothing to approve
    }

    @Test
    void unknownRunIs404AndListFiltersByNeed() throws Exception {
        mvc.perform(get("/match-runs/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/match-runs/" + UUID.randomUUID() + "/results")).andExpect(status().isNotFound());
        mvc.perform(post("/match-runs/" + UUID.randomUUID() + "/approve")).andExpect(status().isNotFound());
        mvc.perform(get("/match-runs?needId=" + UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    // ---- asking again after a failure ----

    String rematchPath(String needId) {
        return "/funding-needs/" + needId + "/match";
    }

    @Test
    void afterAFailedRunTheOwnerCanAskForMatchingAgain() throws Exception {
        long ticket = 7_654_324;
        givenIssuerAndTwoInvestors(ticket);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        // the first run (two model calls) returns garbage, later calls behave
        fake.when(p -> p.contains("amount=" + ticket), (s, u, t) -> attempts.incrementAndGet() <= 2
                ? new LlmResult("no json here", 1, 1) : goodAnswer(u, t, ticket));

        String needId = submit(ticket);
        awaitRun(needId, "FAILED");
        assertThat(db.queryForObject("SELECT status FROM funding_needs WHERE id = ?::uuid", String.class, needId)).isEqualTo("SUBMITTED");

        mvc.perform(post(rematchPath(needId))).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM match_runs WHERE need_id = ?::uuid AND status = 'PENDING_REVIEW'", Integer.class, needId) == 1);

        // already being matched: a second click is refused, so no duplicate runs
        mvc.perform(post(rematchPath(needId))).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT count(*) FROM match_runs WHERE need_id = ?::uuid", Integer.class, needId)).isEqualTo(2);
    }

    @Test
    void rematchNeedsAFailedOrRejectedRunAndOwnership() throws Exception {
        long ticket = 7_654_325;
        givenIssuerAndTwoInvestors(ticket);
        String needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',?,'EQUITY','MEDIUM',?) RETURNING id::text",
                String.class, issuerId, ticket, ticket);

        // freshly submitted, nothing has failed yet: it is already queued
        mvc.perform(post(rematchPath(needId))).andExpect(status().isConflict());

        db.update("INSERT INTO match_runs(need_id,event_id,status) VALUES (?::uuid,gen_random_uuid(),'REJECTED')", needId);
        mvc.perform(post(rematchPath(needId)).with(com.issuermatch.support.Auth.issuer(UUID.randomUUID())))
                .andExpect(status().isNotFound());                       // someone else's need
        mvc.perform(post(rematchPath(needId)).with(com.issuermatch.support.Auth.noRoles())).andExpect(status().isForbidden());
        mvc.perform(post(rematchPath(UUID.randomUUID().toString()))).andExpect(status().isNotFound());

        fake.when(p -> p.contains("amount=" + ticket), (s, u, t) -> goodAnswer(u, t, ticket));
        UUID owner = db.queryForObject("SELECT owner_user_id FROM issuers WHERE id = ?", UUID.class, issuerId);
        mvc.perform(post(rematchPath(needId)).with(com.issuermatch.support.Auth.issuer(owner))).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM match_runs WHERE need_id = ?::uuid AND status = 'PENDING_REVIEW'", Integer.class, needId) == 1);
    }
}
