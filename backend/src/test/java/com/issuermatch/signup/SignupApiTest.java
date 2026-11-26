package com.issuermatch.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.security.Role;
import com.issuermatch.support.Auth;
import com.issuermatch.support.FakeSupabaseAuthClient;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Public signup: only the two outside roles can ever be self-assigned. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SignupApiTest {

    @Autowired MockMvc mvc;
    @Autowired FakeSupabaseAuthClient supabase;
    @Autowired JdbcTemplate db;

    @BeforeEach
    void reset() {
        supabase.reset();
    }

    /** a throwaway password, built at runtime so no credential-shaped literal sits in the source */
    static final String PASSWORD = "pw-" + UUID.randomUUID();

    ResultActions signup(String json) throws Exception {
        return mvc.perform(post("/auth/signup").with(Auth.anonymous()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String body(String email, String role) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"Test Person\",\"role\":" + role + "}";
    }

    String email() {
        return "u-" + UUID.randomUUID() + "@example.test";
    }

    @Test
    void anIssuerCanSignUpWithoutAnyToken() throws Exception {
        String email = email();
        signup(body(email, "\"ISSUER\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ISSUER")).andExpect(jsonPath("$.userId").isNotEmpty());
        assertThat(supabase.created()).singleElement().satisfies(u -> {
            assertThat(u.role()).isEqualTo(Role.ISSUER);
            assertThat(u.email()).isEqualTo(email);
            assertThat(u.fullName()).isEqualTo("Test Person");
        });
    }

    @Test
    void anInvestorCanSignUp() throws Exception {
        signup(body(email(), "\"INVESTOR\"")).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("INVESTOR"));
        assertThat(supabase.created()).extracting(SupabaseAuthAdminClient.NewUser::role).containsExactly(Role.INVESTOR);
    }

    @Test
    void staffAndUnknownRolesAreRefusedAndNothingIsCreated() throws Exception {
        for (String role : new String[] {"\"ADMIN\"", "\"ANALYST\"", "\"admin\"", "\"issuer\"", "\"SUPERUSER\"", "\"\"", "null", "0", "1", "3",
                "[\"ISSUER\",\"ADMIN\"]", "{\"role\":\"ADMIN\"}", "\"ISSUER,ADMIN\"", "\"ISSUER \""}) {
            signup(body(email(), role)).andExpect(status().isBadRequest());
        }
        assertThat(supabase.created()).isEmpty();
    }

    @Test
    void extraFieldsCannotSmuggleInPrivileges() throws Exception {
        for (String extra : new String[] {"\"app_metadata\":{\"roles\":[\"ADMIN\"]}", "\"roles\":[\"ADMIN\"]", "\"userId\":\"x\""}) {
            signup("{\"email\":\"" + email() + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"x\",\"role\":\"ISSUER\"," + extra + "}")
                    .andExpect(status().isBadRequest());
        }
        assertThat(supabase.created()).isEmpty();
    }

    @Test
    void inputIsValidated() throws Exception {
        String ok = "\"fullName\":\"x\",\"role\":\"ISSUER\"";
        signup("{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\"," + ok + "}").andExpect(status().isBadRequest());
        signup("{\"email\":\"" + email() + "\",\"password\":\"short\"," + ok + "}").andExpect(status().isBadRequest());
        signup("{\"email\":\"" + email() + "\",\"password\":\"" + "x".repeat(73) + "\"," + ok + "}").andExpect(status().isBadRequest());
        signup("{\"email\":\"" + email() + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\" \",\"role\":\"ISSUER\"}").andExpect(status().isBadRequest());
        signup("{\"email\":\"" + email() + "\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"" + "x".repeat(101) + "\",\"role\":\"ISSUER\"}")
                .andExpect(status().isBadRequest());
        signup("{}").andExpect(status().isBadRequest());
        assertThat(supabase.created()).isEmpty();
    }

    @Test
    void aTakenEmailIs409() throws Exception {
        String email = email();
        signup(body(email, "\"ISSUER\"")).andExpect(status().isCreated());
        signup(body(email, "\"INVESTOR\"")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void providerFailuresAreGenericAndPasswordsAndEmailsStayOutOfLogsAndAudit(CapturedOutput logs) throws Exception {
        String email = email();
        supabase.failWith(new SupabaseAuthAdminClient.SignupUnavailable("provider said: internal detail"));
        signup(body(email, "\"ISSUER\"")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.message").value("Signup is temporarily unavailable"));
        supabase.reset();
        String id = signup(body(email, "\"ISSUER\"")).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString().replaceAll(".*\"userId\":\"([^\"]+)\".*", "$1");

        assertThat(logs.getAll()).doesNotContain(PASSWORD).doesNotContain(email);
        assertThat(db.queryForObject("SELECT details::text FROM audit_log WHERE action = 'USER_SIGNED_UP' AND resource = ?",
                String.class, "user:" + id)).contains("ISSUER").doesNotContain(email).doesNotContain(PASSWORD);
    }
}
