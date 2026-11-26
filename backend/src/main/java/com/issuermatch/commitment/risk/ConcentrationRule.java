package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Too much of one investor's money in one issuer. Counts what they already put in, plus this amount. */
@Component
class ConcentrationRule implements RiskRule {

    @Override public String id() { return "concentration"; }

    @Override public String description() {
        return "Investor's total exposure to one issuer (existing plus this amount) is flagged above one limit and blocked above a higher one.";
    }

    @Override public Map<String, BigDecimal> defaults() {
        return Map.of("flagAbove", BigDecimal.valueOf(5_000_000), "blockAbove", BigDecimal.valueOf(10_000_000));
    }

    @Override
    public Decision evaluate(RiskContext c, Map<String, BigDecimal> p) {
        BigDecimal existing = c.facts().exposureToIssuer();
        BigDecimal total = existing.add(c.amountUsd());
        String detail = "exposure to this issuer would be " + total.toPlainString()
                + " USD (already " + existing.toPlainString() + " + " + c.amountUsd().toPlainString() + ")";
        if (total.compareTo(p.get("blockAbove")) > 0) {
            return Decision.block("Concentration too high: " + detail + ", limit " + p.get("blockAbove").toPlainString());
        }
        if (total.compareTo(p.get("flagAbove")) > 0) {
            return Decision.flag("Concentration high: " + detail + ", review above " + p.get("flagAbove").toPlainString());
        }
        return Decision.pass("Concentration fine: " + detail);
    }

    @Override
    public void validate(Map<String, BigDecimal> p) {
        RiskRule.requireAtLeast(p, "flagAbove", 1);
        RiskRule.requireNotAbove(p, "flagAbove", "blockAbove");
    }
}
