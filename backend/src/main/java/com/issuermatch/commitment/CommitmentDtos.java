package com.issuermatch.commitment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CommitmentDtos {
    private CommitmentDtos() {}

    record CreateRequest(
            @NotNull UUID needId,
            @NotNull UUID investorId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            // offer context. Investors must say why; stake is for equity deals only
            @DecimalMin("0.001") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal proposedStakePct,
            @Valid ProposedTerms proposedTerms,
            @Size(max = 5000) String investmentRationale) {

        /** Same request, same string: 500000 and 500000.00 and different JSON whitespace all hash alike. */
        String canonical() {
            String base = "needId=" + needId + ";investorId=" + investorId + ";amount=" + amount.stripTrailingZeros().toPlainString();
            if (proposedStakePct == null && proposedTerms == null && investmentRationale == null) {
                return base; // unchanged, so keys saved before offers existed still replay
            }
            // JSON array: free text cannot pose as another field
            return base + ";offer=" + JSON.writeValueAsString(Arrays.asList(
                    proposedStakePct == null ? null : proposedStakePct.stripTrailingZeros().toPlainString(),
                    proposedTerms, investmentRationale));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The terms an investor proposes. Every field is optional. */
    record ProposedTerms(
            @Positive @Digits(integer = 16, fraction = 2) BigDecimal valuation,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 3) BigDecimal interestRatePct,
            @Min(1) @Max(600) Integer tenorMonths,
            @Size(max = 2000) String conditions) {}

    /** Why the commitment got the status it got: one entry per rule that ran. */
    record DecisionView(String rule, String verdict, String reason) {}

    /**
     * status: ACCEPTED (all rules passed), PENDING_REVIEW (a rule flagged it, capacity is reserved until a second
     * person decides) or REJECTED (a rule blocked it, nothing reserved). remainingCapacity is what the raise had left
     * when this was decided.
     */
    record Response(UUID id, UUID needId, UUID investorId, BigDecimal amount, String status,
                    BigDecimal remainingCapacity, List<DecisionView> decisions, Instant createdAt,
                    BigDecimal proposedStakePct, JsonNode proposedTerms, String investmentRationale,
                    String issuerDecision) {}

    enum Status {
        ACCEPTED, PENDING_REVIEW, REJECTED
    }

    /** One row of the staff risk-review queue. */
    record Summary(UUID id, UUID needId, String needTitle, UUID investorId, String investorName, BigDecimal amount,
                   String status, UUID createdBy, Instant createdAt) {}
}
