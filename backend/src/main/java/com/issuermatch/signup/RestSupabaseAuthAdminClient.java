package com.issuermatch.signup;

import com.issuermatch.security.Role;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Supabase Auth Admin API. Uses the server-side service key, which can do anything in the auth system, so:
 * it never leaves this class, is never logged, and this class can only ever create ISSUER or INVESTOR users,
 * whatever its caller asks for.
 */
@Component
class RestSupabaseAuthAdminClient implements SupabaseAuthAdminClient {

    private static final Logger log = LoggerFactory.getLogger(RestSupabaseAuthAdminClient.class);
    static final Set<Role> SELF_SERVICE = Set.of(Role.ISSUER, Role.INVESTOR);

    private final RestClient http;
    private final boolean configured;

    @Autowired
    RestSupabaseAuthAdminClient(@Value("${app.supabase.url:}") String url, @Value("${app.supabase.service-role-key:}") String serviceKey) {
        this(RestClient.builder().requestFactory(timeouts()), url, serviceKey);
    }

    RestSupabaseAuthAdminClient(RestClient.Builder builder, String url, String serviceKey) {
        this.configured = url != null && !url.isBlank() && serviceKey != null && !serviceKey.isBlank();
        if (configured) {
            builder.baseUrl(url).defaultHeader("apikey", serviceKey).defaultHeader("Authorization", "Bearer " + serviceKey);
        } else {
            log.warn("Signup is disabled: SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must both be set");
        }
        this.http = builder.build();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    private record Created(UUID id) {}

    @Override
    public UUID createUser(NewUser user) {
        if (!SELF_SERVICE.contains(user.role())) {
            throw new IllegalArgumentException("Self-service signup cannot create role " + user.role());
        }
        if (!configured) {
            throw new SignupUnavailable("signup is not configured");
        }
        Map<String, Object> body = Map.of(
                "email", user.email(),
                "password", user.password(),
                // confirmed at once so the person can sign in straight away (no email delivery to depend on)
                "email_confirm", true,
                "user_metadata", Map.of("full_name", user.fullName()),
                // app_metadata can only be written with the service key: users cannot change their own role
                "app_metadata", Map.of("roles", List.of(user.role().name())));
        try {
            Created created = http.post().uri("/auth/v1/admin/users").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Created.class);
            if (created == null || created.id() == null) {
                throw new SignupUnavailable("no user id in the response");
            }
            return created.id();
        } catch (RestClientResponseException e) {
            String answer = e.getResponseBodyAsString();
            if (answer.contains("email_exists") || answer.contains("already been registered")) {
                throw new EmailAlreadyRegistered();
            }
            if (answer.contains("weak_password")) {
                throw new PasswordRejected();
            }
            throw new SignupUnavailable("auth provider answered " + e.getStatusCode().value()); // body deliberately not included
        } catch (RestClientException e) {
            throw new SignupUnavailable("auth provider unreachable: " + e.getClass().getSimpleName());
        }
    }
}
