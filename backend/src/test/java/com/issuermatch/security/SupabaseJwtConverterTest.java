package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SupabaseJwtConverterTest {

    Set<String> authorities(Map<String, Object> extraClaims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "ES256").subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        extraClaims.forEach(b::claim);
        return SupabaseJwtConverter.convert(b.build()).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void rolesComeFromAppMetadata() {
        assertThat(authorities(Map.of("app_metadata", Map.of("roles", List.of("ANALYST", "ISSUER")))))
                .containsExactlyInAnyOrder("ROLE_ANALYST", "ROLE_ISSUER");
    }

    @Test
    void userMetadataIsIgnoredBecauseUsersCanEditIt() {
        assertThat(authorities(Map.of("user_metadata", Map.of("roles", List.of("ADMIN"))))).isEmpty();
    }

    @Test
    void theSupabaseDatabaseRoleClaimIsNotAnApplicationRole() {
        assertThat(authorities(Map.of("role", "authenticated"))).isEmpty();
        assertThat(authorities(Map.of("role", "service_role"))).isEmpty();
    }

    @Test
    void unknownAndMalformedRolesAreDropped() {
        assertThat(authorities(Map.of("app_metadata", Map.of("roles", List.of("SUPERUSER", "admin", "ADMIN ", 7)))))
                .isEmpty(); // exact match only: no case folding, no trimming
        assertThat(authorities(Map.of("app_metadata", Map.of("roles", "ADMIN")))).isEmpty();      // not a list
        assertThat(authorities(Map.of("app_metadata", "ADMIN"))).isEmpty();                       // not an object
        assertThat(authorities(Map.of())).isEmpty();
    }

    @Test
    void theUserIdIsTheSubject() {
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(id.toString()).build();
        assertThat(SupabaseJwtConverter.convert(jwt).getName()).isEqualTo(id.toString());
    }
}
