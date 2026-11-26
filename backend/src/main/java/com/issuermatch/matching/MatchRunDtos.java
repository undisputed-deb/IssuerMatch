package com.issuermatch.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class MatchRunDtos {
    private MatchRunDtos() {}

    /** inputFlags: instruction-like content found in the funding text. Non-empty means "read this run carefully". */
    record Summary(UUID id, UUID needId, String status, Integer tokensIn, Integer tokensOut, Integer latencyMs,
                   String error, Instant reviewedAt, Instant createdAt, List<String> inputFlags) {}

    /** Detail adds every tool call the agent made, with inputs and outputs: the explainability trail. */
    record Detail(Summary run, JsonNode toolCalls) {}

    record Result(int rank, UUID investorId, String investorName, BigDecimal score, String explanation,
                  List<String> citedTools) {}
}
