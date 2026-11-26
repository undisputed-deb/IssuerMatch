package com.issuermatch.security;

import com.issuermatch.common.ErrorResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless resource server: every request carries a Supabase JWT, nothing is stored server-side.
 * Default is deny: only /health is public. Fine-grained rules are @PreAuthorize on the controllers
 * plus ownership checks in the services.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JsonMapper json, RateLimiter limiter, RateLimitProperties limits)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(json, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"); // no hint about why
        };
        AccessDeniedHandler forbidden = (request, response, e) ->
                write(json, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");

        http
                .addFilterBefore(RateLimitFilters.perIp(limiter, limits), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(RateLimitFilters.perUser(limiter, limits), BearerTokenAuthenticationFilter.class)
                // CSRF protects cookie sessions. This API has none: it only accepts an Authorization header.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/health").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(SupabaseJwtConverter::convert))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());
        return http.build();
    }

    /**
     * Keys come from Supabase's JWKS endpoint (cached, refreshed when an unknown key id appears).
     * Algorithms are pinned: Supabase signs with ES256. The HS256 shared-secret algorithm is NOT accepted,
     * which closes the classic "sign with a key you control" confusion attack.
     */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri())
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.RS256);
                })
                .build();
        return configure(decoder, props);
    }

    static NimbusJwtDecoder configure(NimbusJwtDecoder decoder, AuthProperties props) {
        decoder.setJwtValidator(validator(props));
        return decoder;
    }

    /** Signature is checked by the decoder. This adds expiry/not-before, exact issuer and exact audience. */
    static OAuth2TokenValidator<Jwt> validator(AuthProperties props) {
        OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(props.issuer());
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(props.audience()));
        return new DelegatingOAuth2TokenValidator<>(issuerAndTime, audience);
    }

    private static void write(JsonMapper json, jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                              String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ErrorResponse.of(code, message));
    }
}
