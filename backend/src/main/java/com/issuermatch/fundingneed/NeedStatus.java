package com.issuermatch.fundingneed;

/** Must match the CHECK constraint on funding_needs.status. */
public enum NeedStatus {
    SUBMITTED, MATCHING, MATCHED, CLOSED
}
