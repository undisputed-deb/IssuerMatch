package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Runs every enabled rule and combines the answers: any BLOCK blocks, else any FLAG flags, else PASS. */
@Service
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    public record RuleResult(String ruleId, Decision decision, Map<String, BigDecimal> params) {}

    public record Outcome(Verdict verdict, List<RuleResult> results) {}

    private final RiskRuleConfigService configs;

    RiskEngine(RiskRuleConfigService configs) {
        this.configs = configs;
    }

    public Outcome evaluate(RiskContext context) {
        List<RuleResult> results = new ArrayList<>();
        for (RiskRuleConfigService.Effective cfg : configs.all()) {
            if (!cfg.enabled()) {
                continue; // switched off by an admin
            }
            results.add(new RuleResult(cfg.rule().id(), run(cfg, context), cfg.params()));
        }
        Verdict overall = results.stream().anyMatch(r -> r.decision().verdict() == Verdict.BLOCK) ? Verdict.BLOCK
                : results.stream().anyMatch(r -> r.decision().verdict() == Verdict.FLAG) ? Verdict.FLAG : Verdict.PASS;
        return new Outcome(overall, results);
    }

    /** A rule that crashes must never wave a commitment through. Fail closed: a human looks at it. */
    private static Decision run(RiskRuleConfigService.Effective cfg, RiskContext context) {
        try {
            return cfg.rule().evaluate(context, cfg.params());
        } catch (RuntimeException e) {
            log.error("Risk rule {} failed", cfg.rule().id(), e);
            return Decision.flag("Rule '" + cfg.rule().id() + "' could not be evaluated, manual review required");
        }
    }
}
