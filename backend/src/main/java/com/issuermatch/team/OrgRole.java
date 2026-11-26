package com.issuermatch.team;

/** What a person may do inside their firm. Declared from least to most: each role can do all the ones before it can. */
public enum OrgRole {
    VIEWER, ANALYST, MANAGING_DIRECTOR;

    public boolean allows(OrgRole needed) {
        return ordinal() >= needed.ordinal();
    }
}
