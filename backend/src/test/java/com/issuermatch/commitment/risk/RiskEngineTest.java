package com.issuermatch.commitment.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Adding a rule is adding a class: this test adds one (that crashes) without touching any engine code. */
@IntegrationTest
@Import(RiskEngineTest.ExtraRules.class)
class RiskEngineTest {

    @TestConfiguration
    static class ExtraRules {
        @Bean
        RiskRule explodingRule() {
            return new RiskRule() {
                public String id() { return "test-explode"; }
                public String description() { return "Always crashes"; }
                public Map<String, BigDecimal> defaults() { return Map.of(); }
                public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) { throw new IllegalStateException("boom"); }
            };
        }
    }

    @Autowired RiskEngine engine;
    @Autowired RiskRuleConfigService configs;
    @Autowired JdbcTemplate db;

    @AfterEach
    void cleanUp() {
        db.update("DELETE FROM risk_rule_config");
    }

    static RiskContext benign() {
        RiskFacts quiet = new RiskFacts() {
            public BigDecimal exposureToIssuer() { return BigDecimal.ZERO; }
            public int investorCommitmentsWithin(Duration w) { return 0; }
            public int identicalCommitmentsWithin(Duration w) { return 0; }
        };
        return new RiskContext(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(500_000), BigDecimal.valueOf(500_000), BigDecimal.valueOf(10_000_000),
                List.of(new RiskContext.TicketRange(BigDecimal.valueOf(100_000), BigDecimal.valueOf(1_000_000))), quiet);
    }

    @Test
    void aNewRuleBeanIsDiscoveredAutomatically() {
        assertThat(configs.all()).extracting(e -> e.rule().id())
                .containsExactly("capacity", "concentration", "duplicate", "test-explode", "ticket-range", "velocity");
    }

    @Test
    void aCrashingRuleFailsClosedIntoAFlagNeverAPass() {
        RiskEngine.Outcome outcome = engine.evaluate(benign());
        assertThat(outcome.verdict()).isEqualTo(Verdict.FLAG);
        assertThat(outcome.results()).filteredOn(r -> r.ruleId().equals("test-explode")).singleElement()
                .satisfies(r -> assertThat(r.decision().reason()).contains("manual review"));
    }

    @Test
    void switchingTheBrokenRuleOffRestoresNormalOperation() {
        configs.update("test-explode", false, Map.of(), UUID.randomUUID());
        assertThat(engine.evaluate(benign()).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void blockBeatsFlagBeatsPass() {
        RiskContext over = new RiskContext(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.valueOf(20_000_000),
                BigDecimal.valueOf(20_000_000), BigDecimal.valueOf(10_000_000), List.of(), benign().facts());
        assertThat(engine.evaluate(over).verdict()).isEqualTo(Verdict.BLOCK); // capacity blocks, the crash only flags
    }
}
