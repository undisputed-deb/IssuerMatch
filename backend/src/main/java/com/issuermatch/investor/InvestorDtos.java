package com.issuermatch.investor;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class InvestorDtos {
    private InvestorDtos() {}

    record InvestorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull InvestorType type,
            @NotNull Region region) {}

    record MandateRequest(
            @NotNull Sector sector,
            @NotNull Region region,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal minTicket,
            @NotNull @Digits(integer = 16, fraction = 2) BigDecimal maxTicket,
            @NotNull RiskLevel riskLevel,
            @NotEmpty @Size(max = 4) List<@NotNull Instrument> instruments) {

        @AssertTrue(message = "maxTicket must be >= minTicket")
        boolean isTicketRangeValid() {
            return minTicket == null || maxTicket == null || maxTicket.compareTo(minTicket) >= 0;
        }

        List<String> instrumentNames() {
            return instruments.stream().map(Enum::name).distinct().toList();
        }
    }

    record MandateResponse(UUID id, Sector sector, Region region, BigDecimal minTicket, BigDecimal maxTicket,
                           RiskLevel riskLevel, List<String> instruments) {
        static MandateResponse from(Mandate m) {
            return new MandateResponse(m.getId(), m.getSector(), m.getRegion(), m.getMinTicket(), m.getMaxTicket(),
                    m.getRiskLevel(), m.getInstruments());
        }
    }

    record InvestorResponse(UUID id, String name, InvestorType type, Region region, Instant createdAt,
                            List<MandateResponse> mandates) {
        static InvestorResponse from(Investor i) {
            return new InvestorResponse(i.getId(), i.getName(), i.getType(), i.getRegion(), i.getCreatedAt(),
                    i.getMandates().stream().map(MandateResponse::from).toList());
        }
    }
}
