package com.issuermatch.security;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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

    /** The address in the verified token, as Supabase recorded it for the account. */
    public static Optional<String> email() {
        return token().map(t -> t.getClaimAsString("email")).filter(e -> !e.isBlank());
    }

    /** The name the user typed at signup. Users can edit it: show it, decide nothing with it. At most 100 characters. */
    public static Optional<String> fullName() {
        return token().map(t -> t.getClaim("user_metadata") instanceof Map<?, ?> m && m.get("full_name") instanceof String s
                        ? s.strip() : "")
                .filter(s -> !s.isEmpty()).map(s -> s.length() > 100 ? s.substring(0, 100) : s);
    }

    private static Optional<Jwt> token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? Optional.of(jwt.getToken()) : Optional.empty();
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }

    /** Analysts and admins see everything; issuers only their own data. */
    public boolean isStaff() {
        return has(Role.ANALYST) || has(Role.ADMIN);
    }
}
