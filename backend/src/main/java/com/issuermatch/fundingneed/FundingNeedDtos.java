package com.issuermatch.fundingneed;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
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
            @NotNull RiskLevel riskLevel) {}

    record Response(UUID id, UUID issuerId, String title, String description, Sector sector, Region region,
                    BigDecimal amount, Instrument instrument, RiskLevel riskLevel, NeedStatus status,
                    BigDecimal remainingCapacity, Instant createdAt) {
        static Response from(FundingNeed n) {
            return new Response(n.getId(), n.getIssuerId(), n.getTitle(), n.getDescription(), n.getSector(),
                    n.getRegion(), n.getAmount(), n.getInstrument(), n.getRiskLevel(), n.getStatus(),
                    n.getRemainingCapacity(), n.getCreatedAt());
        }
    }
}
