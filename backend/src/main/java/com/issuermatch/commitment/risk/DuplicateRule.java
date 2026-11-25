package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The same investor, need and amount again and again: a retry loop, a bug, or someone probing. */
@Component
class DuplicateRule implements RiskRule {

    @Override public String id() { return "duplicate"; }

    @Override public String description() {
        return "Earlier commitments with the same investor, need and amount inside a window: flagged, then blocked if repeated.";
    }

    @Override public Map<String, BigDecimal> defaults() {
        return Map.of("windowMinutes", BigDecimal.valueOf(60), "flagCount", BigDecimal.valueOf(1),
                "blockCount", BigDecimal.valueOf(3));
    }

    @Override
    public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) {
        long minutes = p.get("windowMinutes").longValue();
        int earlier = c.facts().identicalCommitmentsWithin(Duration.ofMinutes(minutes));
        String detail = earlier + " earlier identical commitment(s) (same investor, need and amount) in " + minutes + " min";
        if (earlier >= p.get("blockCount").intValue()) {
            return Decision.block("Repeated pattern: " + detail);
        }
        if (earlier >= p.get("flagCount").intValue()) {
            return Decision.flag("Possible duplicate: " + detail);
        }
        return Decision.pass("No duplicate pattern: " + detail);
    }

    @Override
    public void validate(Map<String, BigDecimal> p) {
        RiskRule.requireAtLeast(p, "windowMinutes", 1);
        RiskRule.requireAtLeast(p, "flagCount", 1);
        RiskRule.requireNotAbove(p, "flagCount", "blockCount");
    }
}
