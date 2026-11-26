package com.issuermatch.admin;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The only code that writes the verified columns: the JPA entities map them read only. */
@Service
public class VerificationService {

    public enum Subject {
        ISSUER("UPDATE issuers SET verified = ? WHERE id = ?", "UPDATE issuers SET verified = false WHERE id = ? AND verified"),
        INVESTOR("UPDATE investors SET verified = ? WHERE id = ?",
                "UPDATE investors SET verified = false WHERE id = ? AND verified");

        private final String update;
        private final String revoke;

        Subject(String update, String revoke) {
            this.update = update;
            this.revoke = revoke;
        }
    }

    private final JdbcTemplate db;
    private final AuditService audit;

    VerificationService(JdbcTemplate db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /**
     * A badge vouches for a name and a website. When either changes it is withdrawn, so a verified account cannot rename
     * itself into someone else and keep the badge. Call inside the transaction that saved the change, after the save.
     *
     * @return true if there was a badge to withdraw
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revokeAfterIdentityChange(Subject subject, UUID id, List<String> changed) {
        if (changed.isEmpty() || db.update(subject.revoke, id) == 0) {
            return false;
        }
        audit.record(subject.name() + "_VERIFICATION_REVOKED", subject.name().toLowerCase() + ":" + id,
                Map.of("changed", changed)); // field names only, never the values
        return true;
    }

    @Transactional
    void set(Subject subject, UUID id, boolean verified) {
        String name = subject.name();
        if (db.update(subject.update, verified, id) == 0) {
            throw new NotFoundException(name.charAt(0) + name.substring(1).toLowerCase() + " not found");
        }
        audit.record(name + (verified ? "_VERIFIED" : "_UNVERIFIED"), name.toLowerCase() + ":" + id, Map.of());
    }
}
