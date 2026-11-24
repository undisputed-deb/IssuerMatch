package com.issuermatch.matching.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * The ranking number. Pure Java, no randomness, no LLM: same inputs always give the same score,
 * so rankings are reproducible and unit-testable.
 *
 * <pre>
 * sector match      30     ticket inside range  30 (outside: 30 x closeness ratio)
 * region match      20     risk same 10 / adjacent 5 / far 0
 * instrument offered 10                                      total 0..100
 * </pre>
 */
public final class FitScorer {

    static final BigDecimal SECTOR = BigDecimal.valueOf(30);
    static final BigDecimal REGION = BigDecimal.valueOf(20);
    static final BigDecimal TICKET = BigDecimal.valueOf(30);
    static final BigDecimal RISK_SAME = BigDecimal.TEN;
    static final BigDecimal RISK_ADJACENT = BigDecimal.valueOf(5);
    static final BigDecimal INSTRUMENT = BigDecimal.TEN;

    private static final Comparator<ScoreBreakdown> BEST_FIRST = Comparator
            .comparing(ScoreBreakdown::total).thenComparing(ScoreBreakdown::ticket).thenComparing(ScoreBreakdown::risk)
            .reversed();

    private FitScorer() {}

    public static ScoreBreakdown score(NeedProfile need, MandateProfile m) {
        return new ScoreBreakdown(
                need.sector() == m.sector() ? SECTOR.setScale(2) : zero(),
                need.region() == m.region() ? REGION.setScale(2) : zero(),
                ticketPoints(need.amount(), m.minTicket(), m.maxTicket()),
                riskPoints(need, m),
                m.instruments().contains(need.instrument().name()) ? INSTRUMENT.setScale(2) : zero());
    }

    /** An investor can hold several mandates. Its score is the best one, whatever order they come in. */
    public static Optional<ScoreBreakdown> best(NeedProfile need, Collection<MandateProfile> mandates) {
        return mandates.stream().map(m -> score(need, m)).sorted(BEST_FIRST).findFirst();
    }

    private static BigDecimal ticketPoints(BigDecimal amount, BigDecimal min, BigDecimal max) {
        if (amount.compareTo(min) >= 0 && amount.compareTo(max) <= 0) {
            return TICKET.setScale(2);
        }
        BigDecimal ratio = amount.compareTo(min) < 0
                ? amount.divide(min, 6, RoundingMode.HALF_UP)
                : max.divide(amount, 6, RoundingMode.HALF_UP);
        return TICKET.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal riskPoints(NeedProfile need, MandateProfile m) {
        int gap = Math.abs(need.riskLevel().ordinal() - m.riskLevel().ordinal());
        BigDecimal points = gap == 0 ? RISK_SAME : gap == 1 ? RISK_ADJACENT : BigDecimal.ZERO;
        return points.setScale(2);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
