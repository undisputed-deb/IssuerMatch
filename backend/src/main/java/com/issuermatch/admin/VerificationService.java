package com.issuermatch.admin;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only code that writes the verified columns: the JPA entities map them read only. */
@Service
class VerificationService {

    enum Subject {
        ISSUER("UPDATE issuers SET verified = ? WHERE id = ?"),
        INVESTOR("UPDATE investors SET verified = ? WHERE id = ?");

        private final String update;

        Subject(String update) {
            this.update = update;
        }
    }

    private final JdbcTemplate db;
    private final AuditService audit;

    VerificationService(JdbcTemplate db, AuditService audit) {
        this.db = db;
        this.audit = audit;
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
