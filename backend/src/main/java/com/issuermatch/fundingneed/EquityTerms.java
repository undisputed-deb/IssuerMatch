package com.issuermatch.fundingneed;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ValueDeserializer.None.class)
public record EquityTerms(
        @Positive BigDecimal targetValuation,
        @PositiveOrZero BigDecimal arrUsd,
        @DecimalMin("-100") @DecimalMax("10000") BigDecimal yoyGrowthPct,
        Boolean boardSeat) implements InstrumentTerms {

    @Override
    public String getType() {
        return "EQUITY";
    }
}
