package com.issuermatch.signup;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.security.Role;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final SupabaseAuthAdminClient auth;
    private final AuditService audit;
    private final TransactionTemplate tx;

    SignupService(SupabaseAuthAdminClient auth, AuditService audit, TransactionTemplate tx) {
        this.auth = auth;
        this.audit = audit;
        this.tx = tx;
    }

    SignupController.Response signup(SignupController.Request r) {
        Role role = Role.valueOf(r.role()); // the request is validated to ISSUER or INVESTOR before it gets here
        UUID userId;
        try {
            userId = auth.createUser(new SupabaseAuthAdminClient.NewUser(r.email(), r.password(), r.fullName().strip(), role));
        } catch (SupabaseAuthAdminClient.EmailAlreadyRegistered e) {
            // This does reveal that an address has an account. Signup cannot avoid saying so; the rate limit slows probing.
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists");
        } catch (SupabaseAuthAdminClient.PasswordRejected e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password was rejected. Use a longer, less common one.");
        } catch (SupabaseAuthAdminClient.SignupUnavailable e) {
            log.warn("Signup failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Signup is temporarily unavailable");
        }
        UUID id = userId;
        // id and role only: no email, name or password in the audit trail
        tx.executeWithoutResult(s -> audit.record("USER_SIGNED_UP", "user:" + id, Map.of("role", role.name())));
        return new SignupController.Response(userId, role.name());
    }
}
