package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The issuer cannot take more than it is still raising. Taking nearly all of what is left is worth a second look. */
@Component
class CapacityRule implements RiskRule {

    @Override public String id() { return "capacity"; }

    @Override public String description() {
        return "Amount above the remaining capacity is blocked. Amount above a share of the remaining capacity is flagged.";
    }

    @Override public Map<String, BigDecimal> defaults() {
        return Map.of("flagPctOfRemaining", BigDecimal.valueOf(90));
    }

    @Override
    public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) {
        BigDecimal remaining = c.remainingCapacity();
        if (c.amount().compareTo(remaining) > 0) {
            return Decision.block("Amount " + c.amount().toPlainString() + " is above the remaining capacity "
                    + remaining.toPlainString());
        }
        BigDecimal pct = p.get("flagPctOfRemaining");
        if (c.amount().multiply(BigDecimal.valueOf(100)).compareTo(remaining.multiply(pct)) > 0) {
            return Decision.flag("Amount takes more than " + pct.toPlainString() + "% of the remaining capacity "
                    + remaining.toPlainString());
        }
        return Decision.pass("Amount fits the remaining capacity " + remaining.toPlainString());
    }

    @Override
    public void validate(Map<String, BigDecimal> p) {
        RiskRule.requireAtLeast(p, "flagPctOfRemaining", 1);
        if (p.get("flagPctOfRemaining").compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("flagPctOfRemaining must not be above 100");
        }
    }
}
