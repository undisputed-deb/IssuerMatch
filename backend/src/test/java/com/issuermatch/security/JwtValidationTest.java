package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Real signatures, no network: proves forged, tampered, expired and mis-addressed tokens are refused. */
class JwtValidationTest {

    static final String ISSUER = "https://proj.supabase.co/auth/v1";
    static final AuthProperties PROPS = new AuthProperties("http://unused", ISSUER, "authenticated");

    static ECPrivateKey privateKey;
    static NimbusJwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        privateKey = (ECPrivateKey) pair.getPrivate();
        // Same shape as production: a key set, and a key selector that only accepts ES256 signatures.
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).build()))));
        decoder = SecurityConfig.configure(new NimbusJwtDecoder(processor), PROPS);
    }

    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).issuer(ISSUER).audience("authenticated")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).issueTime(new Date());
    }

    static String sign(JWTClaimsSet c) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), c);
        jwt.sign(new ECDSASigner(privateKey));
        return jwt.serialize();
    }

    @Test
    void acceptsAValidToken() throws Exception {
        Jwt jwt = decoder.decode(sign(claims().build()));
        assertThat(jwt.getSubject()).isNotBlank();
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String token = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(3600))).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAWrongIssuer() throws Exception {
        String token = sign(claims().issuer("https://evil.example/auth/v1").build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAWrongOrMissingAudience() throws Exception {
        assertThatThrownBy(() -> decoder.decode(sign(claims().audience("anon").build()))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(claims().audience(List.of()).build()))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenWhosePayloadWasTampered() throws Exception {
        String[] parts = sign(claims().build()).split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims().claim("app_metadata", Map.of("roles", List.of("ADMIN"))).build().toString().getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2]; // original signature, new payload
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnUnsignedAlgNoneToken() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(claims().build().toString().getBytes());
        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnHs256TokenSignedWithAnyKey() throws Exception {
        // classic algorithm-confusion attack: attacker signs with HMAC using a key they control
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        jwt.sign(new MACSigner(new byte[32]));
        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsATokenSignedByADifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.ES256), claims().build());
        jwt.sign(new ECDSASigner((ECPrivateKey) gen.generateKeyPair().getPrivate()));
        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[] {"", "abc", "a.b.c", "....", "eyJhbGciOiJFUzI1NiJ9.e30."}) {
            assertThatThrownBy(() -> decoder.decode(bad)).as(bad).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void validatorChainIsBuiltFromProperties() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.validator(PROPS);
        assertThat(v).isInstanceOf(DelegatingOAuth2TokenValidator.class);
    }
}
