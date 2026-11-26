package com.issuermatch.investor;

/** How an investor takes part in a round. Must match the CHECK constraint on investors.participation_role. */
public enum ParticipationRole {
    LEAD, CO_LEAD, FOLLOW_ON
}
