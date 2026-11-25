package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One risk check. To add a rule: write a class that implements this and annotate it @Component. Nothing else.
 * The engine finds it, runs it on every commitment, stores its decision, and the admin API can tune its thresholds.
 */
public interface RiskRule {

    /** Stable id: the key for stored configuration and for risk_decisions rows. */
    String id();

    String description();

    /** The threshold names this rule understands, with their built-in values. Also the list of allowed keys. */
    Map<String, BigDecimal> defaults();

    /** @param params the defaults overlaid with whatever an admin configured. Always contains every default key. */
    Decision evaluate(RiskContext context, Map<String, BigDecimal> params);

    /** Reject nonsense thresholds (negative numbers, flag above block, ...). Throw IllegalArgumentException. */
    default void validate(Map<String, BigDecimal> params) {}

    // ---- helpers shared by the rules ----

    static void requireAtLeast(Map<String, BigDecimal> p, String key, long min) {
        if (p.get(key).compareTo(BigDecimal.valueOf(min)) < 0) {
            throw new IllegalArgumentException(key + " must be at least " + min);
        }
    }

    static void requireNotAbove(Map<String, BigDecimal> p, String low, String high) {
        if (p.get(low).compareTo(p.get(high)) > 0) {
            throw new IllegalArgumentException(low + " must not be above " + high);
        }
    }
}
