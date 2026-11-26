package com.issuermatch.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.matching.agent.AgentFailure;
import com.issuermatch.matching.agent.FakeLlmClient;
import com.issuermatch.matching.agent.LlmResult;
import com.issuermatch.matching.agent.MatchAgent;
import com.issuermatch.matching.agent.ModelOutput;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** What happens when the model is broken, over budget or repeatedly failing. */
@IntegrationTest
@Transactional
class MatchExecutorTest {

    @Autowired JdbcTemplate db;
    @Autowired MatchAgent agent;
    @Autowired DeterministicMatcher fallback;
    @Autowired FakeLlmClient llm;

    final AtomicInteger modelCalls = new AtomicInteger();

    MatchExecutor executor(long budget) {
        return new MatchExecutor(db, agent, fallback, budget, 2, 2, 50, Duration.ofMinutes(1));
    }

    UUID need(long amount) {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'INDUSTRIALS','MEA',?) RETURNING id",
                UUID.class, "Ex " + UUID.randomUUID(), UUID.randomUUID());
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                + " VALUES (?,'Plant upgrade','d','INDUSTRIALS','MEA',?,'DEBT','LOW',?) RETURNING id", UUID.class, issuer, amount, amount);
    }

    UUID investor(String name, String risk) {
        UUID id = db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'PRIVATE_CREDIT','MEA') RETURNING id", UUID.class, name);
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'INDUSTRIALS','MEA',1,900000000,?,'{DEBT}')", id, risk);
        return id;
    }

    void modelThrows(long amount) {
        llm.when(p -> p.contains("amount=" + amount), (s, u, t) -> {
            modelCalls.incrementAndGet();
            throw new IllegalStateException("provider outage");
        });
    }

    @Test
    void aModelOutageDegradesToDeterministicMatchingInsteadOfDroppingTheDeal() {
        UUID good = investor("Ex Good " + UUID.randomUUID(), "LOW");
        UUID fair = investor("Ex Fair " + UUID.randomUUID(), "HIGH");
        UUID n = need(4_111_001);
        modelThrows(4_111_001);

        MatchExecutor.Outcome o = executor(1_000_000_000).execute(n);

        assertThat(o.mode()).isEqualTo(MatchExecutor.Mode.DEGRADED_DETERMINISTIC);
        assertThat(o.degradedReason()).isEqualTo("Model call failed");
        List<UUID> ranked = o.result().rankings().stream().map(ModelOutput.Entry::investorId).toList();
        assertThat(ranked).containsSubsequence(good, fair); // best score first
        assertThat(o.result().rankings()).allSatisfy(e -> {
            assertThat(e.suggestedDiligenceQuestions()).hasSize(3);
            assertThat(e.suggestedDiligenceQuestions().get(0)).contains("debt service"); // template by instrument
            assertThat(e.dealBrief()).contains("Plant upgrade").contains("generated automatically");
        });
        assertThat(o.result().run().tokensIn()).isZero();
    }

    @Test
    void atMostFiveQualifiedInvestorsAndNeverADisqualifiedOne() {
        for (int i = 0; i < 14; i++) investor("Ex Many " + UUID.randomUUID(), "LOW");
        UUID excluded = investor("Ex Excluded " + UUID.randomUUID(), "LOW");
        db.update("UPDATE investors SET investor_type = 'VC' WHERE id = ?", excluded);
        UUID n = need(4_111_002);
        db.update("UPDATE funding_needs SET hard_constraints = '{\"excludedInvestorTypes\":[\"VC\"]}' WHERE id = ?", n);

        MatchAgent.Result r = fallback.match(n);

        assertThat(r.rankings()).hasSize(5).extracting(ModelOutput.Entry::investorId).doesNotContain(excluded);
    }

    @Test
    void afterRepeatedFailuresTheCircuitOpensAndTheModelIsNotCalled() {
        investor("Ex Breaker " + UUID.randomUUID(), "LOW");
        UUID n = need(4_111_003);
        modelThrows(4_111_003);
        MatchExecutor ex = executor(1_000_000_000);
        ex.execute(n);
        ex.execute(n);
        int callsBefore = modelCalls.get();

        MatchExecutor.Outcome third = ex.execute(n);

        assertThat(modelCalls.get()).isEqualTo(callsBefore); // the broken provider is left alone
        assertThat(third.degradedReason()).contains("circuit is open");
        assertThat(third.mode()).isEqualTo(MatchExecutor.Mode.DEGRADED_DETERMINISTIC);
    }

    @Test
    void aSpentDailyTokenBudgetSkipsTheModel() {
        investor("Ex Budget " + UUID.randomUUID(), "LOW");
        UUID n = need(4_111_004);
        modelThrows(4_111_004);
        db.update("INSERT INTO llm_daily_usage(usage_day,tokens) VALUES (current_date, 5000)"
                + " ON CONFLICT (usage_day) DO UPDATE SET tokens = 5000");

        MatchExecutor.Outcome o = executor(5000).execute(n);

        assertThat(modelCalls.get()).isZero();
        assertThat(o.degradedReason()).isEqualTo("Daily token budget reached");
    }

    @Test
    void everyModelAnswerCountsTowardsTheDailyBudgetEvenARejectedOne() {
        investor("Ex Usage " + UUID.randomUUID(), "LOW");
        UUID n = need(4_111_005);
        llm.when(p -> p.contains("amount=4111005"), (s, u, t) -> new LlmResult("not json", 700, 30));
        long before = db.queryForObject("SELECT coalesce(sum(tokens),0) FROM llm_daily_usage WHERE usage_day = current_date", Long.class);

        executor(1_000_000_000).execute(n);

        assertThat(db.queryForObject("SELECT sum(tokens) FROM llm_daily_usage WHERE usage_day = current_date", Long.class) - before)
                .isEqualTo(1460); // two attempts of 730
    }

    @Test
    void withNobodyQualifiedTheFailureSaysWhy() {
        UUID n = need(4_111_006);
        db.update("UPDATE funding_needs SET sector = 'CONSUMER', hard_constraints = '{\"minInvestorDeployableUsd\": 1}' WHERE id = ?", n);
        modelThrows(4_111_006);
        assertThatThrownBy(() -> executor(1_000_000_000).execute(n)).isInstanceOf(AgentFailure.class)
                .hasMessageContaining("No qualified investors").hasMessageContaining("Model call failed");
    }
}
