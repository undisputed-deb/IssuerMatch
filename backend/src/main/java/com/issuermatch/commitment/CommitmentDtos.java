package com.issuermatch.commitment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CommitmentDtos {
    private CommitmentDtos() {}

    record CreateRequest(
            @NotNull UUID needId,
            @NotNull UUID investorId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount) {

        /** Same request, same string: 500000 and 500000.00 and different JSON whitespace all hash alike. */
        String canonical() {
            return "needId=" + needId + ";investorId=" + investorId + ";amount=" + amount.stripTrailingZeros().toPlainString();
        }
    }

    /** Why the commitment got the status it got: one entry per rule that ran. */
    record DecisionView(String rule, String verdict, String reason) {}

    /**
     * status: ACCEPTED (all rules passed), PENDING_REVIEW (a rule flagged it, capacity is reserved until a second
     * person decides) or REJECTED (a rule blocked it, nothing reserved). remainingCapacity is what the raise had left
     * when this was decided.
     */
    record Response(UUID id, UUID needId, UUID investorId, BigDecimal amount, String status,
                    BigDecimal remainingCapacity, List<DecisionView> decisions, Instant createdAt) {}
}
