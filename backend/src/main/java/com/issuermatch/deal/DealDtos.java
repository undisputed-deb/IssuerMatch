package com.issuermatch.deal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class DealDtos {
    private DealDtos() {}

    /** An offer as the issuer and staff see it: who is offering, how much, on what terms, why, and the issuer's answer. */
    record Offer(UUID commitmentId, UUID investorId, String investorFirm, String investorType, String contactName,
                 String contactRole, BigDecimal amount, String currency, String status, BigDecimal proposedStakePct,
                 JsonNode proposedTerms, String investmentRationale, Instant createdAt, String issuerDecision,
                 BigDecimal aumOrRevenueUsd, BigDecimal deployableCapitalUsd, String websiteUrl, boolean verified) {}

    /** One of the calling investor's own offers. */
    record MyOffer(UUID commitmentId, UUID needId, String needTitle, BigDecimal amount, String currency, String status,
                   BigDecimal proposedStakePct, JsonNode proposedTerms, String investmentRationale, Instant createdAt,
                   String issuerDecision) {}

    record Question(UUID id, UUID needId, UUID investorId, String investorFirm, String question, String answer,
                    Instant createdAt, Instant answeredAt) {}

    record AskRequest(@NotBlank @Size(max = 2000) String question) {}

    record AnswerRequest(@NotBlank @Size(max = 4000) String answer) {}

    record PassRequest(@Size(max = 1000) String reason) {}
}
