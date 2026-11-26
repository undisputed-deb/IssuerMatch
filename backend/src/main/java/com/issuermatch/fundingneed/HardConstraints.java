package com.issuermatch.fundingneed;

import com.issuermatch.common.Region;
import com.issuermatch.investor.InvestorType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Deal breakers set by the issuer. An investor that violates any of them is disqualified, however well it scores
 * otherwise. Everything is optional: an empty object means no hard constraints.
 */
public record HardConstraints(
        @Size(max = 5) List<@NotNull Region> allowedInvestorRegions,
        @Size(max = 6) List<@NotNull InvestorType> excludedInvestorTypes,
        Boolean requireInstrumentMatch,
        Boolean requireRegionMatch,
        @PositiveOrZero BigDecimal minInvestorDeployableUsd) {

    public static final HardConstraints NONE = new HardConstraints(null, null, null, null, null);
}
