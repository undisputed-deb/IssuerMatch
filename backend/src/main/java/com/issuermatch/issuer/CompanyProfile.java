package com.issuermatch.issuer;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * What a company says about itself. Everything is optional, and all text is untrusted. runwayMonths and dataRoomUrl are
 * for the owner and staff: investors never receive them (see InvestorFeed).
 */
@Embeddable
public record CompanyProfile(
        @Size(max = 100) String contactName,
        @Size(max = 100) String contactRole,
        // only http and https: a "javascript:" link would run in whoever clicks it
        @Size(max = 300) @Pattern(regexp = CompanyProfile.WEB_ADDRESS, message = "must be an http(s) address") String websiteUrl,
        @Size(max = 2000) String companySummary,
        @Min(1800) @Max(2100) Integer foundedYear,
        @Pattern(regexp = "1-10|11-50|51-200|201-1000|1000\\+", message = "unknown team size") String teamSizeBand,
        @PositiveOrZero @Digits(integer = 18, fraction = 2) BigDecimal totalRaisedUsd,
        @Min(0) @Max(600) Integer runwayMonths,
        @Size(max = 500) @Pattern(regexp = CompanyProfile.WEB_ADDRESS, message = "must be an http(s) address") String dataRoomUrl) {

    static final String WEB_ADDRESS = "https?://[A-Za-z0-9.-]+(:\\d{1,5})?([/?#][^\\s<>\"']*)?";

    static final CompanyProfile EMPTY = new CompanyProfile(null, null, null, null, null, null, null, null, null);
}
