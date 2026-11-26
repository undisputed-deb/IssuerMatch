package com.issuermatch.commitment;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The issuer closes the loop on an offer that risk rules or a reviewer accepted: take it, or turn it down. A decision is
 * final. Declining gives the reserved capacity back to the raise, in the same transaction as the decision and its event.
 */
@Service
class IssuerDecisionService {

    record Decided(UUID commitmentId, UUID needId, String status, String issuerDecision, Instant issuerDecidedAt,
                   BigDecimal remainingCapacity) {}

    private final JdbcTemplate db;
    private final AuditService audit;
    private final CommitmentEvents events;

    IssuerDecisionService(JdbcTemplate db, AuditService audit, CommitmentEvents events) {
        this.db = db;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    Decided decide(UUID commitmentId, boolean accept, UUID userId) {
        // an offer on someone else's raise looks exactly like a missing one
        List<UUID> need = db.queryForList("SELECT c.need_id FROM commitments c JOIN funding_needs n ON n.id = c.need_id"
                + " JOIN issuers s ON s.id = n.issuer_id WHERE c.id = ? AND s.owner_user_id = ?", UUID.class, commitmentId, userId);
        if (need.isEmpty()) {
            throw new NotFoundException("Commitment not found");
        }
        UUID needId = need.get(0);
        // same first lock as a new commitment, so capacity is never given back while an offer is being judged against it
        db.queryForList("SELECT id FROM funding_needs WHERE id = ? FOR UPDATE", UUID.class, needId);
        String decision = accept ? "ACCEPTED" : "DECLINED";
        // conditional update: of two decisions at once only one finds the offer still PENDING
        var changed = db.queryForList("UPDATE commitments SET issuer_decision = ?, issuer_decided_at = clock_timestamp(),"
                + " issuer_decided_by = ? WHERE id = ? AND status = 'ACCEPTED' AND issuer_decision = 'PENDING'"
                + " RETURNING investor_id, amount, issuer_decided_at", decision, userId, commitmentId);
        if (changed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Only an accepted offer that you have not decided yet can be decided");
        }
        var row = changed.get(0);
        BigDecimal amount = (BigDecimal) row.get("amount");
        if (!accept) {
            db.update("UPDATE funding_needs SET remaining_capacity = remaining_capacity + ?, version = version + 1 WHERE id = ?",
                    amount, needId);
            events.declined(new CommitmentEvents.Facts(commitmentId, needId, (UUID) row.get("investor_id"), amount));
        }
        audit.record("COMMITMENT_ISSUER_" + decision, "commitment:" + commitmentId,
                Map.of("needId", needId.toString(), "amount", amount.toPlainString()));
        BigDecimal remaining = db.queryForObject("SELECT remaining_capacity FROM funding_needs WHERE id = ?", BigDecimal.class, needId);
        return new Decided(commitmentId, needId, "ACCEPTED", decision,
                ((java.sql.Timestamp) row.get("issuer_decided_at")).toInstant(), remaining);
    }
}
