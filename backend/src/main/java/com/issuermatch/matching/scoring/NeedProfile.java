package com.issuermatch.matching.scoring;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import com.issuermatch.fundingneed.CompanyStage;
import com.issuermatch.fundingneed.HardConstraints;
import java.math.BigDecimal;

/**
 * What scoring needs to know about a funding need. Money is already in US dollars.
 *
 * @param amountUsd    the whole raise
 * @param minTicketUsd the smallest ticket the issuer accepts; null means one investor is expected to take the amount
 * @param stage        the company's stage, or null when the issuer did not say
 */
public record NeedProfile(Sector sector, Region region, BigDecimal amountUsd, BigDecimal minTicketUsd,
                          Instrument instrument, RiskLevel riskLevel, HardConstraints constraints, CompanyStage stage) {}
