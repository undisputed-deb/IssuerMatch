package com.issuermatch.matching.scoring;

import java.math.BigDecimal;

/** Points per criterion. Kept so the explanation can say exactly why an investor scored what it did. */
public record ScoreBreakdown(BigDecimal sector, BigDecimal region, BigDecimal ticket, BigDecimal risk,
                             BigDecimal instrument) {

    public BigDecimal total() {
        return sector.add(region).add(ticket).add(risk).add(instrument);
    }
}
