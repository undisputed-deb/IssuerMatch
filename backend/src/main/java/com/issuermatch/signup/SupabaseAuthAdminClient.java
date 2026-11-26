package com.issuermatch.signup;

import com.issuermatch.security.Role;
import java.util.UUID;

/** Creating login accounts. Production talks to Supabase's Auth Admin API; tests use an in-memory fake. */
public interface SupabaseAuthAdminClient {

    record NewUser(String email, String password, String fullName, Role role) {
        @Override
        public String toString() {
            return "NewUser[role=" + role + "]"; // never let an email or password reach a log line by accident
        }
    }

    /** @return the new user's id (the "sub" of the tokens it will receive) */
    UUID createUser(NewUser user);

    class EmailAlreadyRegistered extends RuntimeException {}

    class PasswordRejected extends RuntimeException {}

    /** Not configured, unreachable or refused. The message is for our log and holds no secret. */
    class SignupUnavailable extends RuntimeException {
        public SignupUnavailable(String message) {
            super(message);
        }
    }
}
