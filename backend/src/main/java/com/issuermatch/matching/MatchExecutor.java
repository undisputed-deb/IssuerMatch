package com.issuermatch.matching;

import com.issuermatch.matching.agent.AgentFailure;
import com.issuermatch.matching.agent.MatchAgent;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Chooses how a match runs. Normally the agent. If the model fails, the circuit is open (it failed repeatedly) or the
 * day's token budget is spent, the deterministic matcher takes over and the run is labelled DEGRADED_DETERMINISTIC.
 * A human still reviews the result either way.
 */
@Component
class MatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(MatchExecutor.class);

    enum Mode {
        AGENT, DEGRADED_DETERMINISTIC
    }

    record Outcome(MatchAgent.Result result, Mode mode, String degradedReason) {}

    private final JdbcTemplate db;
    private final MatchAgent agent;
    private final DeterministicMatcher fallback;
    private final long dailyTokenBudget;
    private final CircuitBreaker breaker;

    MatchExecutor(JdbcTemplate db, MatchAgent agent, DeterministicMatcher fallback,
                  @Value("${app.agent.daily-token-budget:500000}") long dailyTokenBudget,
                  @Value("${app.agent.breaker.minimum-calls:4}") int minimumCalls,
                  @Value("${app.agent.breaker.window:10}") int window,
                  @Value("${app.agent.breaker.failure-rate:50}") float failureRate,
                  @Value("${app.agent.breaker.open-for:60s}") Duration openFor) {
        this.db = db;
        this.agent = agent;
        this.fallback = fallback;
        this.dailyTokenBudget = dailyTokenBudget;
        this.breaker = CircuitBreaker.of("language-model", CircuitBreakerConfig.custom()
                .slidingWindowSize(window).minimumNumberOfCalls(minimumCalls).failureRateThreshold(failureRate)
                .waitDurationInOpenState(openFor).build());
    }

    /** @throws AgentFailure only when even the deterministic matcher has nothing to offer */
    Outcome execute(UUID needId) {
        Long used = db.queryForObject("SELECT coalesce(sum(tokens), 0) FROM llm_daily_usage WHERE usage_day = current_date", Long.class);
        if (used != null && used >= dailyTokenBudget) {
            return degrade(needId, "Daily token budget reached");
        }
        try {
            return new Outcome(breaker.executeSupplier(() -> agent.match(needId)), Mode.AGENT, null);
        } catch (CallNotPermittedException e) {
            return degrade(needId, "Model circuit is open after repeated failures");
        } catch (AgentFailure e) {
            return degrade(needId, e.getMessage());
        }
    }

    private Outcome degrade(UUID needId, String reason) {
        log.warn("Matching need {} without the model: {}", needId, reason);
        try {
            return new Outcome(fallback.match(needId), Mode.DEGRADED_DETERMINISTIC, reason);
        } catch (AgentFailure e) {
            throw new AgentFailure(e.getMessage() + " (" + reason + ")");
        }
    }
}
