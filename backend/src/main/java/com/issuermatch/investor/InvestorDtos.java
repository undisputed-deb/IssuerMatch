package com.issuermatch.investor;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InvestorDtos {
    private InvestorDtos() {}

    /** Staff entry of a basic profile. */
    record InvestorRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull InvestorType investorType,
            @NotNull Region region) {}

    /**
     * An investor's own institutional profile and mandate preferences (replaces any earlier mandates).
     * The thesis is untrusted free text: it is length capped and, when used by the model, treated as data.
     */
    public record ProfileRequest(
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Size(max = 100) String roleTitle,
            @NotBlank @Size(max = 200) String companyName,
            // only http and https: a "javascript:" link would run in whoever clicks it
            @Size(max = 300) @Pattern(regexp = "https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/[^\\s<>\"']*)?", message = "must be an http(s) address") String websiteUrl,
            @NotNull InvestorType investorType,
            @NotNull Region region,
            @PositiveOrZero @Digits(integer = 18, fraction = 2) BigDecimal aumOrRevenueUsd,
            @NotNull @Positive @Digits(integer = 18, fraction = 2) BigDecimal deployableCapitalUsd,
            @NotNull @Pattern(regexp = com.issuermatch.common.Currencies.PATTERN, message = "unsupported currency (ISO 4217 code)") String currency,
            @NotBlank @Size(max = 10000) String longThesisDescription,
            @NotEmpty @Size(max = 10) List<@NotNull @Valid MandateRequest> mandates) {}

    public record MandateRequest(
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

    record InvestorResponse(UUID id, String name, InvestorType investorType, Region region, Instant createdAt,
                            List<MandateResponse> mandates, boolean profileCompleted, String contactName, String roleTitle,
                            String companyName, String websiteUrl, BigDecimal aumOrRevenueUsd, BigDecimal deployableCapitalUsd,
                            String currency, String longThesisDescription) {
        static InvestorResponse from(Investor i) {
            return from(i, i.getMandates());
        }

        static InvestorResponse from(Investor i, List<Mandate> mandates) {
            return new InvestorResponse(i.getId(), i.getName(), i.getInvestorType(), i.getRegion(), i.getCreatedAt(),
                    mandates.stream().map(MandateResponse::from).toList(), i.isProfileCompleted(), i.getContactName(),
                    i.getRoleTitle(), i.getCompanyName(), i.getWebsiteUrl(), i.getAumOrRevenueUsd(), i.getDeployableCapitalUsd(),
                    i.getCurrency(), i.getLongThesisDescription());
        }
    }
}
