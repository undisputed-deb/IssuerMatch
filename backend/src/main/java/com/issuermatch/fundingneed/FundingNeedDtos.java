package com.issuermatch.fundingneed;

import com.issuermatch.common.Currencies;
import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class FundingNeedDtos {
    private FundingNeedDtos() {}

    // description is untrusted free text (it ends up in an LLM prompt): length-capped here and in the database.
    record CreateRequest(
            @NotNull UUID issuerId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 5000) String description,
            @NotNull Sector sector,
            @NotNull Region region,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @NotNull Instrument instrument,
            @NotNull RiskLevel riskLevel,
            @Pattern(regexp = Currencies.PATTERN, message = "unsupported currency (ISO 4217 code)") String currency,
            CompanyStage companyStage,
            @FutureOrPresent LocalDate targetCloseDate,
            @Size(max = 10) List<@NotBlank @Size(max = 200) String> useOfFunds,
            @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal minTicketSize,
            @Valid HardConstraints hardConstraints,
            @Valid InstrumentTerms instrumentTerms) {

        @AssertTrue(message = "minTicketSize must not be above amount")
        boolean isMinTicketWithinAmount() {
            return minTicketSize == null || amount == null || minTicketSize.compareTo(amount) <= 0;
        }

        @AssertTrue(message = "instrumentTerms type does not match the instrument")
        boolean isTermsMatchingInstrument() {
            return instrumentTerms == null || instrument == null || instrumentTerms.fits(instrument);
        }
    }

    record Response(UUID id, UUID issuerId, String title, String description, Sector sector, Region region,
                    BigDecimal amount, Instrument instrument, RiskLevel riskLevel, NeedStatus status,
                    BigDecimal remainingCapacity, Instant createdAt, String currency, CompanyStage companyStage,
                    LocalDate targetCloseDate, List<String> useOfFunds, BigDecimal minTicketSize, HardConstraints hardConstraints,
                    InstrumentTerms instrumentTerms) {
        static Response from(FundingNeed n) {
            return new Response(n.getId(), n.getIssuerId(), n.getTitle(), n.getDescription(), n.getSector(),
                    n.getRegion(), n.getAmount(), n.getInstrument(), n.getRiskLevel(), n.getStatus(),
                    n.getRemainingCapacity(), n.getCreatedAt(), n.getCurrency(), n.getCompanyStage(), n.getTargetCloseDate(),
                    n.getUseOfFunds(), n.getMinTicketSize(), n.getHardConstraints(), n.getInstrumentTerms());
        }
    }
}
