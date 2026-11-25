package com.issuermatch.commitment.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.commitment.risk.RiskContext.TicketRange;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure rule logic, no database: every rule has a PASS, a FLAG and a BLOCK case, plus boundaries and tuning. */
class RiskRulesTest {

    static BigDecimal n(long v) {
        return BigDecimal.valueOf(v);
    }

    /** Facts with fixed answers. */
    record Facts(long exposure, int recent, int identical) implements RiskFacts {
        public BigDecimal exposureToIssuer() { return n(exposure); }
        public int investorCommitmentsWithin(Duration w) { return recent; }
        public int identicalCommitmentsWithin(Duration w) { return identical; }
    }

    static RiskContext ctx(long amount, long remaining, List<TicketRange> ranges, Facts facts) {
        return new RiskContext(UUID.randomUUID(), UUID.randomUUID(), n(amount), n(remaining), ranges, facts);
    }

    static RiskContext ctx(long amount) {
        return ctx(amount, 10_000_000, List.of(new TicketRange(n(100_000), n(1_000_000))), new Facts(0, 0, 0));
    }

    static Verdict verdict(RiskRule rule, RiskContext c, Map<String, BigDecimal>... overrides) {
        Map<String, BigDecimal> params = new HashMap<>(rule.defaults());
        for (Map<String, BigDecimal> o : overrides) params.putAll(o);
        return rule.evaluate(c, params).verdict();
    }

    // ---- ticket-range: default block beyond 50% outside ----
    final RiskRule ticket = new TicketRangeRule();

    @Test
    void ticketRange_insideIsPassIncludingTheBoundaries() {
        assertThat(verdict(ticket, ctx(500_000))).isEqualTo(Verdict.PASS);
        assertThat(verdict(ticket, ctx(100_000))).isEqualTo(Verdict.PASS);
        assertThat(verdict(ticket, ctx(1_000_000))).isEqualTo(Verdict.PASS);
    }

    @Test
    void ticketRange_slightlyOutsideIsFlagFarOutsideIsBlock() {
        assertThat(verdict(ticket, ctx(1_200_000))).isEqualTo(Verdict.FLAG);   // 20% above max
        assertThat(verdict(ticket, ctx(80_000))).isEqualTo(Verdict.FLAG);      // 20% below min
        assertThat(verdict(ticket, ctx(1_500_000))).isEqualTo(Verdict.FLAG);   // exactly 50%: still a flag
        assertThat(verdict(ticket, ctx(1_500_001))).isEqualTo(Verdict.BLOCK);  // just beyond
        assertThat(verdict(ticket, ctx(40_000))).isEqualTo(Verdict.BLOCK);     // 60% below
    }

    @Test
    void ticketRange_usesTheBestFittingMandate() {
        RiskContext two = ctx(5_000_000, 10_000_000,
                List.of(new TicketRange(n(100_000), n(1_000_000)), new TicketRange(n(4_000_000), n(6_000_000))), new Facts(0, 0, 0));
        assertThat(verdict(ticket, two)).isEqualTo(Verdict.PASS);
    }

    @Test
    void ticketRange_noMandateIsFlagged() {
        assertThat(verdict(ticket, ctx(1, 10, List.of(), new Facts(0, 0, 0)))).isEqualTo(Verdict.FLAG);
    }

    @Test
    void ticketRange_thresholdIsTunable() {
        assertThat(verdict(ticket, ctx(1_600_000))).isEqualTo(Verdict.BLOCK);
        assertThat(verdict(ticket, ctx(1_600_000), Map.of("blockOutsidePct", n(80)))).isEqualTo(Verdict.FLAG);
    }

    // ---- concentration: flag above 5M, block above 10M (existing + new) ----
    final RiskRule concentration = new ConcentrationRule();

    RiskContext withExposure(long amount, long exposure) {
        return ctx(amount, 100_000_000, List.of(), new Facts(exposure, 0, 0));
    }

    @Test
    void concentration_passFlagBlock() {
        assertThat(verdict(concentration, withExposure(1_000_000, 4_000_000))).isEqualTo(Verdict.PASS);   // 5.0M total, not above
        assertThat(verdict(concentration, withExposure(1_000_001, 4_000_000))).isEqualTo(Verdict.FLAG);   // just above 5M
        assertThat(verdict(concentration, withExposure(2_000_000, 8_000_000))).isEqualTo(Verdict.FLAG);   // exactly 10M
        assertThat(verdict(concentration, withExposure(2_000_001, 8_000_000))).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void concentration_reasonExplainsTheArithmetic() {
        Decision d = concentration.evaluate(withExposure(3_000_000, 4_000_000), concentration.defaults());
        assertThat(d.reason()).contains("7000000").contains("4000000").contains("3000000");
    }

    // ---- velocity: window 10 min, flag at 5, block at 10 (counting this one) ----
    final RiskRule velocity = new VelocityRule();

    RiskContext withRecent(int recent) {
        return ctx(1, 10, List.of(), new Facts(0, recent, 0));
    }

    @Test
    void velocity_passFlagBlock() {
        assertThat(verdict(velocity, withRecent(3))).isEqualTo(Verdict.PASS);   // this is the 4th
        assertThat(verdict(velocity, withRecent(4))).isEqualTo(Verdict.FLAG);   // 5th
        assertThat(verdict(velocity, withRecent(8))).isEqualTo(Verdict.FLAG);   // 9th
        assertThat(verdict(velocity, withRecent(9))).isEqualTo(Verdict.BLOCK);  // 10th
    }

    @Test
    void velocity_isTunable() {
        assertThat(verdict(velocity, withRecent(2), Map.of("flagCount", n(3), "blockCount", n(4)))).isEqualTo(Verdict.FLAG);
        assertThat(verdict(velocity, withRecent(3), Map.of("flagCount", n(3), "blockCount", n(4)))).isEqualTo(Verdict.BLOCK);
    }

    // ---- capacity: block above remaining, flag above 90% of it ----
    final RiskRule capacity = new CapacityRule();

    RiskContext ofRemaining(long amount, long remaining) {
        return ctx(amount, remaining, List.of(), new Facts(0, 0, 0));
    }

    @Test
    void capacity_passFlagBlock() {
        assertThat(verdict(capacity, ofRemaining(900_000, 1_000_000))).isEqualTo(Verdict.PASS);    // exactly 90%
        assertThat(verdict(capacity, ofRemaining(900_001, 1_000_000))).isEqualTo(Verdict.FLAG);
        assertThat(verdict(capacity, ofRemaining(1_000_000, 1_000_000))).isEqualTo(Verdict.FLAG);  // all of it: allowed, reviewed
        assertThat(verdict(capacity, ofRemaining(1_000_001, 1_000_000))).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void capacity_isTunable() {
        assertThat(verdict(capacity, ofRemaining(500_001, 1_000_000), Map.of("flagPctOfRemaining", n(50)))).isEqualTo(Verdict.FLAG);
        assertThat(verdict(capacity, ofRemaining(500_000, 1_000_000), Map.of("flagPctOfRemaining", n(50)))).isEqualTo(Verdict.PASS);
    }

    // ---- duplicate: same investor+need+amount; flag at 1 earlier, block at 3 ----
    final RiskRule duplicate = new DuplicateRule();

    RiskContext withIdentical(int earlier) {
        return ctx(1, 10, List.of(), new Facts(0, 0, earlier));
    }

    @Test
    void duplicate_passFlagBlock() {
        assertThat(verdict(duplicate, withIdentical(0))).isEqualTo(Verdict.PASS);
        assertThat(verdict(duplicate, withIdentical(1))).isEqualTo(Verdict.FLAG);
        assertThat(verdict(duplicate, withIdentical(2))).isEqualTo(Verdict.FLAG);
        assertThat(verdict(duplicate, withIdentical(3))).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void duplicate_isTunable() {
        assertThat(verdict(duplicate, withIdentical(1), Map.of("flagCount", n(2)))).isEqualTo(Verdict.PASS);
    }

    // ---- validation of admin-supplied thresholds ----

    static Map<String, BigDecimal> with(RiskRule r, Map<String, BigDecimal> o) {
        Map<String, BigDecimal> m = new HashMap<>(r.defaults());
        m.putAll(o);
        return m;
    }

    @Test
    void defaultsAreValidForEveryRule() {
        for (RiskRule r : List.of(ticket, concentration, velocity, capacity, duplicate)) {
            r.validate(r.defaults()); // must not throw
        }
    }

    @Test
    void nonsenseThresholdsAreRejected() {
        assertThatThrownBy(() -> concentration.validate(with(concentration, Map.of("flagAbove", n(9), "blockAbove", n(5)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flagAbove");
        assertThatThrownBy(() -> velocity.validate(with(velocity, Map.of("flagCount", n(0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> velocity.validate(with(velocity, Map.of("windowMinutes", n(0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capacity.validate(with(capacity, Map.of("flagPctOfRemaining", n(101)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ticket.validate(with(ticket, Map.of("blockOutsidePct", n(0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> duplicate.validate(with(duplicate, Map.of("flagCount", n(5), "blockCount", n(2)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRuleHasAStableIdAndDescription() {
        assertThat(List.of(ticket, concentration, velocity, capacity, duplicate))
                .extracting(RiskRule::id).containsExactly("ticket-range", "concentration", "velocity", "capacity", "duplicate");
        assertThat(List.of(ticket, concentration, velocity, capacity, duplicate))
                .allSatisfy(r -> assertThat(r.description()).isNotBlank());
    }
}
