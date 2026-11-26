package com.issuermatch.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.issuermatch.security.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The real client against a mock server: what we send to Supabase, and how its answers are mapped. */
class RestSupabaseAuthAdminClientTest {

    static final String KEY = "test-" + "key";
    static final String URL = "https://project.example.test";

    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final RestSupabaseAuthAdminClient client = new RestSupabaseAuthAdminClient(builder, URL, KEY);

    SupabaseAuthAdminClient.NewUser user(Role role) {
        return new SupabaseAuthAdminClient.NewUser("a@example.test", "pw-" + UUID.randomUUID(), "A Person", role);
    }

    @Test
    void createsAConfirmedUserWithTheRoleInAppMetadata() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(URL + "/auth/v1/admin/users")).andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", KEY)).andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(content().json("""
                        {"email":"a@example.test","email_confirm":true,
                         "user_metadata":{"full_name":"A Person"},"app_metadata":{"roles":["INVESTOR"]}}"""))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"email\":\"a@example.test\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createUser(user(Role.INVESTOR))).isEqualTo(id);
        server.verify();
    }

    @Test
    void refusesToCreateStaffEvenIfSomeCallerAsks() {
        for (Role staff : new Role[] {Role.ADMIN, Role.ANALYST}) {
            assertThatThrownBy(() -> client.createUser(user(staff))).isInstanceOf(IllegalArgumentException.class);
        }
        server.verify(); // nothing was sent
    }

    @Test
    void anExistingEmailIsReportedAsSuch() {
        server.expect(requestTo(URL + "/auth/v1/admin/users")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"error_code\":\"email_exists\",\"msg\":\"already registered\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.createUser(user(Role.ISSUER))).isInstanceOf(SupabaseAuthAdminClient.EmailAlreadyRegistered.class);
    }

    @Test
    void aWeakPasswordIsReportedAsSuch() {
        server.expect(requestTo(URL + "/auth/v1/admin/users")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"error_code\":\"weak_password\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.createUser(user(Role.ISSUER))).isInstanceOf(SupabaseAuthAdminClient.PasswordRejected.class);
    }

    @Test
    void anythingElseIsUnavailableWithoutEchoingTheResponse() {
        server.expect(requestTo(URL + "/auth/v1/admin/users")).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("bad key " + KEY));
        assertThatThrownBy(() -> client.createUser(user(Role.ISSUER)))
                .isInstanceOf(SupabaseAuthAdminClient.SignupUnavailable.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
    }

    @Test
    void withoutAConfiguredKeySignupIsUnavailableAndNothingIsSent() {
        RestSupabaseAuthAdminClient unconfigured = new RestSupabaseAuthAdminClient(builder, URL, " ");
        assertThatThrownBy(() -> unconfigured.createUser(user(Role.ISSUER))).isInstanceOf(SupabaseAuthAdminClient.SignupUnavailable.class);
        server.verify();
    }
}
