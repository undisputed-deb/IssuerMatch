package com.issuermatch.matching.scoring;

import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import java.math.BigDecimal;
import java.util.List;

/** One mandate, with its ticket range already converted to US dollars. */
public record MandateProfile(Sector sector, Region region, BigDecimal minUsd, BigDecimal maxUsd,
                             RiskLevel riskLevel, List<String> instruments, List<String> targetStages) {}
