package com.issuermatch.matching.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ranking number. Pure Java: same facts, same score. No randomness and no language model.
 *
 * <pre>
 * soft preferences (points, 0..100 in total)
 *   mandateFit       25   sector 20 + instrument offered 5
 *   ticketFit        25   mandate range overlaps what the issuer can accept (else 25 x closeness)
 *   regionFit        15
 *   riskFit          10   same level 10, one step apart 5
 *   trackRecord      10   4 per past deal in the sector, 2 per past deal in the region, capped
 *   thesisAlignment  15   15 x cosine similarity of thesis and need
 * hard constraints (disqualify, set by the issuer)
 *   excluded type, region not allowed, instrument or region mandate required, minimum deployable capital,
 *   and: no mandate can write the smallest ticket the issuer accepts
 * </pre>
 * The four mandate-level criteria come from the investor's single best mandate.
 */
public final class FitScorer {

    private static final BigDecimal SECTOR = points(20);
    private static final BigDecimal INSTRUMENT = points(5);
    private static final BigDecimal TICKET = points(25);
    private static final BigDecimal REGION = points(15);
    private static final BigDecimal RISK_SAME = points(10);
    private static final BigDecimal RISK_ADJACENT = points(5);
    private static final int TRACK_CAP = 10;
    private static final BigDecimal THESIS = points(15);

    private record MandateScore(BigDecimal mandate, BigDecimal ticket, BigDecimal region, BigDecimal risk) {
        BigDecimal sum() {
            return mandate.add(ticket).add(region).add(risk);
        }
    }

    private FitScorer() {}

    public static ScoreBreakdown score(NeedProfile need, InvestorFacts investor) {
        MandateScore best = investor.mandates().stream().map(m -> scoreMandate(need, m))
                .max(Comparator.comparing(MandateScore::sum).thenComparing(MandateScore::ticket).thenComparing(MandateScore::risk))
                .orElse(new MandateScore(points(0), points(0), points(0), points(0)));
        int track = Math.min(TRACK_CAP, 4 * investor.sectorDeals() + 2 * investor.regionDeals());
        double similarity = investor.thesisSimilarity() == null ? 0 : Math.clamp(investor.thesisSimilarity(), 0, 1);
        BigDecimal thesis = THESIS.multiply(BigDecimal.valueOf(similarity)).setScale(2, RoundingMode.HALF_UP);
        return new ScoreBreakdown(best.mandate(), best.ticket(), best.region(), best.risk(), points(track), thesis,
                disqualifiers(need, investor));
    }

    private static MandateScore scoreMandate(NeedProfile need, MandateProfile m) {
        BigDecimal mandate = (need.sector() == m.sector() ? SECTOR : points(0))
                .add(m.instruments().contains(need.instrument().name()) ? INSTRUMENT : points(0));
        int gap = Math.abs(need.riskLevel().ordinal() - m.riskLevel().ordinal());
        return new MandateScore(mandate, ticketPoints(need, m), need.region() == m.region() ? REGION : points(0),
                gap == 0 ? RISK_SAME : gap == 1 ? RISK_ADJACENT : points(0));
    }

    /** Full points when the mandate range overlaps [smallest accepted ticket, whole amount]. */
    private static BigDecimal ticketPoints(NeedProfile need, MandateProfile m) {
        BigDecimal low = need.minTicketUsd() == null ? need.amountUsd() : need.minTicketUsd();
        BigDecimal high = need.amountUsd();
        if (m.maxUsd().compareTo(low) < 0) {
            return TICKET.multiply(m.maxUsd()).divide(low, 2, RoundingMode.HALF_UP);
        }
        if (m.minUsd().compareTo(high) > 0) {
            return TICKET.multiply(high).divide(m.minUsd(), 2, RoundingMode.HALF_UP);
        }
        return TICKET;
    }

    private static List<String> disqualifiers(NeedProfile need, InvestorFacts investor) {
        var c = need.constraints();
        List<String> out = new ArrayList<>();
        if (c.excludedInvestorTypes() != null && c.excludedInvestorTypes().contains(investor.type())) {
            out.add("investor type " + investor.type() + " is excluded by the issuer");
        }
        if (c.allowedInvestorRegions() != null && !c.allowedInvestorRegions().isEmpty()
                && !c.allowedInvestorRegions().contains(investor.region())) {
            out.add("investor region " + investor.region() + " is not allowed by the issuer");
        }
        if (Boolean.TRUE.equals(c.requireInstrumentMatch())
                && investor.mandates().stream().noneMatch(m -> m.instruments().contains(need.instrument().name()))) {
            out.add("no mandate includes " + need.instrument() + ", which the issuer requires");
        }
        if (Boolean.TRUE.equals(c.requireRegionMatch()) && investor.mandates().stream().noneMatch(m -> m.region() == need.region())) {
            out.add("no mandate covers " + need.region() + ", which the issuer requires");
        }
        if (c.minInvestorDeployableUsd() != null && (investor.deployableUsd() == null
                || investor.deployableUsd().compareTo(c.minInvestorDeployableUsd()) < 0)) {
            out.add("deployable capital is below the issuer's minimum or unknown");
        }
        if (need.minTicketUsd() != null && !investor.mandates().isEmpty()
                && investor.mandates().stream().allMatch(m -> m.maxUsd().compareTo(need.minTicketUsd()) < 0)) {
            out.add("largest mandate ticket is below the smallest ticket the issuer accepts");
        }
        return List.copyOf(out);
    }

    private static BigDecimal points(int n) {
        return BigDecimal.valueOf(n).setScale(2);
    }
}
