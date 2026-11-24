package com.issuermatch.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The caller, read from the verified token. Immutable snapshot. */
public record CurrentUser(UUID id, Set<Role> roles) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new AuthenticationCredentialsNotFoundException("Not authenticated");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority a : auth.getAuthorities()) {
            for (Role r : Role.values()) {
                if (a.getAuthority().equals("ROLE_" + r.name())) roles.add(r);
            }
        }
        try {
            return new CurrentUser(UUID.fromString(auth.getName()), Set.copyOf(roles));
        } catch (IllegalArgumentException e) {
            throw new AuthenticationCredentialsNotFoundException("Not a user token");
        }
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** Analysts and admins see everything; issuers only their own data. */
    public boolean isStaff() {
        return has(Role.ANALYST) || has(Role.ADMIN);
    }
}
