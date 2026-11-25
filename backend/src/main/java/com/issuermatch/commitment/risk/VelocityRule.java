package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Too many commitment attempts from one investor in a short time. */
@Component
class VelocityRule implements RiskRule {

    @Override public String id() { return "velocity"; }

    @Override public String description() {
        return "Number of commitments by one investor within a time window: flagged at one count, blocked at a higher one.";
    }

    @Override public Map<String, BigDecimal> defaults() {
        return Map.of("windowMinutes", BigDecimal.valueOf(10), "flagCount", BigDecimal.valueOf(5),
                "blockCount", BigDecimal.valueOf(10));
    }

    @Override
    public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) {
        long minutes = p.get("windowMinutes").longValue();
        int count = c.facts().investorCommitmentsWithin(Duration.ofMinutes(minutes)) + 1; // +1 = this one
        String detail = count + " commitments by this investor in " + minutes + " min";
        if (count >= p.get("blockCount").intValue()) {
            return Decision.block("Velocity too high: " + detail + " (block at " + p.get("blockCount").toPlainString() + ")");
        }
        if (count >= p.get("flagCount").intValue()) {
            return Decision.flag("Velocity high: " + detail + " (review at " + p.get("flagCount").toPlainString() + ")");
        }
        return Decision.pass("Velocity fine: " + detail);
    }

    @Override
    public void validate(Map<String, BigDecimal> p) {
        RiskRule.requireAtLeast(p, "windowMinutes", 1);
        RiskRule.requireAtLeast(p, "flagCount", 1);
        RiskRule.requireNotAbove(p, "flagCount", "blockCount");
    }
}
