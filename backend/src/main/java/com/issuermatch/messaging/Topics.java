package com.issuermatch.messaging;

public final class Topics {
    private Topics() {}

    public static final String FUNDING_NEED_SUBMITTED = "funding-need.submitted";
    public static final String MATCH_REQUESTED = "match.requested";
    public static final String MATCH_COMPLETED = "match.completed";
    public static final String AUDIT_EVENTS = "audit.events";
    public static final String COMMITMENT_REQUESTED = "commitment.requested";
    public static final String COMMITMENT_ACCEPTED = "commitment.accepted";
    public static final String COMMITMENT_REJECTED = "commitment.rejected";
    public static final String COMMITMENT_DECLINED = "commitment.declined";

    /** Topics this application has a consumer for. Dead letters may be replayed only into these. */
    public static final java.util.Set<String> CONSUMED = java.util.Set.of(FUNDING_NEED_SUBMITTED, MATCH_REQUESTED,
            COMMITMENT_REQUESTED, COMMITMENT_ACCEPTED, COMMITMENT_REJECTED, COMMITMENT_DECLINED);
}
