package com.issuermatch.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Which of the fields a Verified badge vouches for are different. */
public final class IdentityChange {

    private IdentityChange() {}

    public static List<String> between(String oldName, String oldWebsite, String newName, String newWebsite) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(oldName, newName)) {
            changed.add("name");
        }
        if (!Objects.equals(oldWebsite, newWebsite)) {
            changed.add("website");
        }
        return List.copyOf(changed);
    }
}
