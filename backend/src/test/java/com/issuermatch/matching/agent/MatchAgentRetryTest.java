package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Committed rows (agent tools run on another thread). Fake model only. */
@IntegrationTest
class MatchAgentRetryTest {

    @Autowired JdbcTemplate db;
    @Autowired MatchingTools tools;
    @Autowired JsonMapper mapper;

    UUID needId;
    UUID investorId;
    UUID issuerId;

    /** This test commits rows, so it must remove them (other tests assume e.g. an empty investors table). */
    @AfterEach
    void cleanUp() {
        if (investorId != null) db.update("DELETE FROM investors WHERE id = ?", investorId);
        if (issuerId != null) db.update("DELETE FROM issuers WHERE id = ?", issuerId);
    }

    void givenOneMatchingInvestor() {
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Retry " + UUID.randomUUID(), UUID.randomUUID());
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1234567,'EQUITY','MEDIUM',1234567) RETURNING id",
                UUID.class, issuerId);
        investorId = db.queryForObject("INSERT INTO investors(name,type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "Retry Inv " + UUID.randomUUID());
        // A ticket range only this test's need fits, so other tests' investors cannot interfere with the assertions.
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',1234567,1234567,'MEDIUM','{EQUITY}')", investorId);
    }

    /** What a well-behaved model does: search, score, then answer. */
    String gather(List<ToolCallback> t) {
        FakeLlmClient.callTool(t, "searchInvestorsByMandate", "{\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ticketSize\":1234567}");
        FakeLlmClient.callTool(t, "scoreFit", "{\"needId\":\"" + needId + "\",\"investorId\":\"" + investorId + "\"}");
        return "{\"rankings\":[{\"investorId\":\"" + investorId + "\",\"explanation\":\"Exact ticket and sector fit.\","
                + "\"citedTools\":[\"scoreFit\",\"searchInvestorsByMandate\"]}]}";
    }

    MatchAgent agent(FakeLlmClient llm) {
        return new MatchAgent(db, llm, tools, mapper, 12, Duration.ofSeconds(10));
    }

    @Test
    void validAnswerOnTheFirstTryNeedsOneModelCall() {
        givenOneMatchingInvestor();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns((s, u, t) -> new LlmResult(gather(t), 100, 20));

        MatchAgent.Result result = agent(llm).match(needId);

        assertThat(result.rankings()).singleElement().extracting(ModelOutput.Entry::investorId).isEqualTo(investorId);
        assertThat(llm.calls()).hasSize(1);
        assertThat(result.run().toolCalls()).hasSize(2);
    }

    @Test
    void invalidFirstAnswerIsRetriedOnceWithTheReasonAndThenAccepted() {
        givenOneMatchingInvestor();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> new LlmResult("Sorry, I cannot produce JSON.", 100, 20),
                (s, u, t) -> new LlmResult(gather(t), 100, 20));

        MatchAgent.Result result = agent(llm).match(needId);

        assertThat(llm.calls()).hasSize(2);
        assertThat(llm.calls().get(1).userPrompt()).contains("rejected").contains("no JSON object");
        assertThat(result.rankings()).hasSize(1);
        assertThat(result.run().tokensIn()).isEqualTo(200); // both attempts counted
        assertThat(result.run().tokensOut()).isEqualTo(40);
    }

    @Test
    void secondInvalidAnswerFailsSafelyAndDoesNotRetryAgain() {
        givenOneMatchingInvestor();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> new LlmResult("nope", 1, 1),
                (s, u, t) -> new LlmResult("still nope", 1, 1),
                (s, u, t) -> new LlmResult("must never be asked", 1, 1));

        assertThatThrownBy(() -> agent(llm).match(needId))
                .isInstanceOf(AgentFailure.class).hasMessage("Model output invalid after one retry");
        assertThat(llm.calls()).hasSize(2);
    }

    @Test
    void anInventedInvestorIsRejectedEvenIfTheJsonIsPerfect() {
        givenOneMatchingInvestor();
        String invented = "{\"rankings\":[{\"investorId\":\"" + UUID.randomUUID() + "\",\"explanation\":\"Trust me.\","
                + "\"citedTools\":[\"scoreFit\"]}]}";
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> { gather(t); return new LlmResult(invented, 1, 1); },
                (s, u, t) -> { gather(t); return new LlmResult(invented, 1, 1); });

        assertThatThrownBy(() -> agent(llm).match(needId)).isInstanceOf(AgentFailure.class);
    }

    @Test
    void retryDoesNotGetAFreshToolBudget() {
        givenOneMatchingInvestor();
        java.util.List<String> retryOutputs = new java.util.ArrayList<>();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> { // burns the whole budget of 12, then answers badly
                    for (int i = 0; i < 12; i++) FakeLlmClient.callTool(t, "getMarketContext", "{\"sector\":\"FINTECH\"}");
                    return new LlmResult("not json", 1, 1);
                },
                (s, u, t) -> { // tries 5 more calls: every one must be refused
                    for (int i = 0; i < 5; i++) retryOutputs.add(FakeLlmClient.callTool(t, "getMarketContext", "{\"sector\":\"FINTECH\"}"));
                    return new LlmResult("still not json", 1, 1);
                });

        assertThatThrownBy(() -> agent(llm).match(needId)).isInstanceOf(AgentFailure.class);

        assertThat(retryOutputs).hasSize(5).containsOnly(RunToolbox.BUDGET_MESSAGE); // 12 total, never 24
    }

    @Test
    void retryPromptCarriesTheToolResultsAlreadyGathered() {
        givenOneMatchingInvestor();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> { gather(t); return new LlmResult("oops", 1, 1); },
                (s, u, t) -> new LlmResult(answerFor(), 1, 1)); // no tool calls needed: it already has the results

        MatchAgent.Result result = agent(llm).match(needId);

        String retryPrompt = llm.calls().get(1).userPrompt();
        assertThat(retryPrompt).contains("already gathered").contains("searchInvestorsByMandate").contains("scoreFit");
        assertThat(result.rankings()).hasSize(1);
        assertThat(result.run().toolCalls()).hasSize(2); // cumulative, not duplicated
    }

    String answerFor() {
        return "{\"rankings\":[{\"investorId\":\"" + investorId + "\",\"explanation\":\"Exact fit.\","
                + "\"citedTools\":[\"scoreFit\",\"searchInvestorsByMandate\"]}]}";
    }

    @Test
    void theDeadlineCoversBothAttemptsTogether() {
        givenOneMatchingInvestor();
        FakeLlmClient llm = new FakeLlmClient().scriptTurns(
                (s, u, t) -> { sleep(400); return new LlmResult("not json", 1, 1); },
                (s, u, t) -> { sleep(400); return new LlmResult(answerFor(), 1, 1); });
        MatchAgent tight = new MatchAgent(db, llm, tools, mapper, 12, Duration.ofMillis(600));

        // 400ms + 400ms > 600ms overall, so the second attempt must be cut off
        assertThatThrownBy(() -> tight.match(needId)).isInstanceOf(AgentFailure.class).hasMessageContaining("timed out");
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
