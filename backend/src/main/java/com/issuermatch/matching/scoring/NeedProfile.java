package com.issuermatch.matching.scoring;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import java.math.BigDecimal;

/** The facts of a funding need that matter for scoring. Plain data, so the scorer needs no database. */
public record NeedProfile(Sector sector, Region region, BigDecimal amount, Instrument instrument, RiskLevel riskLevel) {}
