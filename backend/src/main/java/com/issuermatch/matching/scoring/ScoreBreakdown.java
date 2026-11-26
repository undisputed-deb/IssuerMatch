package com.issuermatch.matching.scoring;

import java.math.BigDecimal;
import java.util.List;

/**
 * Points per criterion, so an explanation can say exactly why. Disqualifiers are broken hard constraints: an investor
 * with any is never ranked, however many points it has.
 */
public record ScoreBreakdown(BigDecimal mandateFit, BigDecimal ticketFit, BigDecimal regionFit, BigDecimal riskFit,
                             BigDecimal trackRecord, BigDecimal thesisAlignment, List<String> disqualifiers) {

    public BigDecimal total() {
        return mandateFit.add(ticketFit).add(regionFit).add(riskFit).add(trackRecord).add(thesisAlignment);
    }

    public boolean qualified() {
        return disqualifiers.isEmpty();
    }
}
