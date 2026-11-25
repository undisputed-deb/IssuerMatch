package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Is the amount inside what this investor's mandate says it invests? Slightly outside: review. Far outside: reject. */
@Component
class TicketRangeRule implements RiskRule {

    @Override public String id() { return "ticket-range"; }

    @Override public String description() {
        return "Amount must fit the investor's mandate ticket range. Outside it is flagged, far outside is blocked.";
    }

    @Override public Map<String, BigDecimal> defaults() {
        return Map.of("blockOutsidePct", BigDecimal.valueOf(50));
    }

    @Override
    public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) {
        if (c.mandateRanges().isEmpty()) {
            return Decision.flag("Investor has no mandate on file to compare the amount against");
        }
        BigDecimal nearest = c.mandateRanges().stream().map(r -> distancePct(c.amount(), r))
                .min(BigDecimal::compareTo).orElseThrow();
        if (nearest.signum() == 0) {
            return Decision.pass("Amount " + c.amount().toPlainString() + " is inside a mandate range");
        }
        BigDecimal limit = p.get("blockOutsidePct");
        String why = "Amount is " + nearest.setScale(1, RoundingMode.HALF_UP).toPlainString()
                + "% outside the nearest mandate range";
        return nearest.compareTo(limit) > 0
                ? Decision.block(why + " (limit " + limit.toPlainString() + "%)")
                : Decision.flag(why);
    }

    /** 0 when inside the range, otherwise how far outside as a percentage of the bound it missed. */
    private static BigDecimal distancePct(BigDecimal amount, RiskContext.TicketRange r) {
        if (amount.compareTo(r.min()) < 0) {
            return r.min().subtract(amount).multiply(BigDecimal.valueOf(100)).divide(r.min(), 6, RoundingMode.HALF_UP);
        }
        if (amount.compareTo(r.max()) > 0) {
            return amount.subtract(r.max()).multiply(BigDecimal.valueOf(100)).divide(r.max(), 6, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    @Override
    public void validate(Map<String, BigDecimal> p) {
        RiskRule.requireAtLeast(p, "blockOutsidePct", 1);
    }
}
