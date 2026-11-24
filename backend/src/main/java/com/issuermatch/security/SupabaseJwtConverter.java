package com.issuermatch.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified Supabase JWT into an authenticated user.
 * Roles are read ONLY from app_metadata.roles (set server-side). Never from user_metadata (users can edit it)
 * and never from the top-level "role" claim (that is the Postgres role, always "authenticated").
 */
public final class SupabaseJwtConverter {

    private SupabaseJwtConverter() {}

    public static JwtAuthenticationToken convert(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Token subject is not a user id");
        }
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private static List<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> result = new ArrayList<>();
        if (!(jwt.getClaim("app_metadata") instanceof Map<?, ?> appMetadata)) {
            return result;
        }
        if (!(appMetadata.get("roles") instanceof List<?> roles)) {
            return result;
        }
        for (Object r : roles) {
            if (r instanceof String name) {
                for (Role known : Role.values()) {
                    if (known.name().equals(name)) { // exact match only
                        result.add(new SimpleGrantedAuthority("ROLE_" + known.name()));
                    }
                }
            }
        }
        return result;
    }
}
