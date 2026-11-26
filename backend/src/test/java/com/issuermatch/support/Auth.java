package com.issuermatch.support;

import com.issuermatch.security.SupabaseJwtConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Logs a test request in as a user with the given roles. The token goes through the REAL converter,
 * so the role-extraction rules are exercised by every authorised test. (Signature checking has its own test.)
 */
public final class Auth {

    private Auth() {}

    public static RequestPostProcessor as(UUID userId, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "ES256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("app_metadata", Map.of("roles", List.of(roles))).build();
        return SecurityMockMvcRequestPostProcessors.authentication(SupabaseJwtConverter.convert(jwt));
    }

    public static RequestPostProcessor admin() {
        return as(UUID.fromString("00000000-0000-0000-0000-0000000000ad"), "ADMIN");
    }

    public static RequestPostProcessor analyst() {
        return as(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "ANALYST");
    }

    public static RequestPostProcessor issuer(UUID userId) {
        return as(userId, "ISSUER");
    }

    public static RequestPostProcessor investor(UUID userId) {
        return as(userId, "INVESTOR");
    }

    /** An investor whose token carries an email address, as a real Supabase token does. */
    public static RequestPostProcessor investor(UUID userId, String email) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "ES256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).claim("email", email)
                .claim("app_metadata", Map.of("roles", List.of("INVESTOR"))).build();
        return SecurityMockMvcRequestPostProcessors.authentication(SupabaseJwtConverter.convert(jwt));
    }

    public static RequestPostProcessor noRoles() {
        return as(UUID.randomUUID());
    }

    public static RequestPostProcessor anonymous() {
        return SecurityMockMvcRequestPostProcessors.anonymous();
    }
}
