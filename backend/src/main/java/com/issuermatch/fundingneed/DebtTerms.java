package com.issuermatch.fundingneed;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ValueDeserializer.None.class)
public record DebtTerms(
        @Min(1) @Max(600) Integer tenorMonths,
        Seniority seniority,
        @Size(max = 100) String collateralType,
        @DecimalMin("0") @DecimalMax("100") BigDecimal targetYieldPct,
        BigDecimal ebitdaUsd) implements InstrumentTerms {

    public enum Seniority {
        SENIOR_SECURED, SENIOR_UNSECURED, SUBORDINATED, MEZZANINE
    }

    @Override
    public String getType() {
        return "DEBT";
    }
}
