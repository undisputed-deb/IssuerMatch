package com.issuermatch.matching.scoring;

import com.issuermatch.common.Region;
import com.issuermatch.investor.InvestorType;
import com.issuermatch.investor.ParticipationRole;
import java.math.BigDecimal;
import java.util.List;

/**
 * What scoring needs to know about an investor.
 *
 * @param sectorDeals      past deals in the need's sector
 * @param regionDeals      past deals in the need's region
 * @param thesisSimilarity cosine similarity of thesis and need, or null when either is not embedded
 * @param participation    how the investor takes part in rounds, or null when not said
 * @param paused           true when the investor is not deploying capital right now
 */
public record InvestorFacts(InvestorType type, Region region, BigDecimal deployableUsd, List<MandateProfile> mandates,
                            int sectorDeals, int regionDeals, Double thesisSimilarity, ParticipationRole participation,
                            boolean paused) {}
