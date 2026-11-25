package com.issuermatch.commitment.risk;

import java.math.BigDecimal;
import java.time.Duration;

/** Questions a rule may ask about history. Answered lazily from the database, so a rule only pays for what it uses. */
public interface RiskFacts {

    /** What this investor already has committed (accepted or pending) to this need's issuer, across all its needs. */
    BigDecimal exposureToIssuer();

    /** How many commitments this investor attempted (any outcome) in the window. */
    int investorCommitmentsWithin(Duration window);

    /** How many earlier commitments had the same investor, need and amount in the window. */
    int identicalCommitmentsWithin(Duration window);
}
