package com.issuermatch.fundingneed;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = ValueDeserializer.None.class)
public record ConvertibleTerms(
        @Positive BigDecimal valuationCap,
        @DecimalMin("0") @DecimalMax("100") BigDecimal discountPct,
        @DecimalMin("0") @DecimalMax("100") BigDecimal interestRatePct) implements InstrumentTerms {

    @Override
    public String getType() {
        return "CONVERTIBLE";
    }
}
