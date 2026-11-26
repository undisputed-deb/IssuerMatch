package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything a rule needs to judge one requested commitment. amount, remainingCapacity and mandateRanges are in the
 * funding need's currency; amountUsd and RiskFacts.exposureToIssuer are in US dollars, for limits that span needs.
 */
public record RiskContext(UUID needId, UUID investorId, BigDecimal amount, BigDecimal amountUsd, BigDecimal remainingCapacity,
                          List<TicketRange> mandateRanges, RiskFacts facts) {

    /** An investor's min/max ticket from one mandate that fits this need. */
    public record TicketRange(BigDecimal min, BigDecimal max) {}
}
