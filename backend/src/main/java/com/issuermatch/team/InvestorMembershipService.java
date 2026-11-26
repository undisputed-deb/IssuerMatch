package com.issuermatch.team;

import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which firm a user works for and in what role, decided from the database on every request. Nothing a client sends is
 * believed.
 *
 * <p>The owner of a profile counts as its managing director only while the firm has no member rows at all (profiles
 * inserted directly, never through the app). Once a firm has members, the member rows are the whole truth, so an owner
 * who was removed from the team does not come back through the old owner column.
 */
@Service
public class InvestorMembershipService {

    public record Membership(UUID investorId, OrgRole orgRole) {}

    private final JdbcTemplate db;

    InvestorMembershipService(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Membership> find(UUID userId) {
        return db.query("SELECT investor_id, org_role FROM investor_members WHERE user_id = ?"
                        + " UNION ALL SELECT i.id, 'MANAGING_DIRECTOR' FROM investors i WHERE i.owner_user_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM investor_members m WHERE m.investor_id = i.id OR m.user_id = ?)",
                (rs, n) -> new Membership(rs.getObject("investor_id", UUID.class), OrgRole.valueOf(rs.getString("org_role"))),
                userId, userId, userId).stream().findFirst();
    }

    /** The caller's firm, if their role is high enough. No firm: 404. Role too low: 403. */
    public Membership require(UUID userId, OrgRole needed) {
        Membership membership = find(userId).orElseThrow(() -> new NotFoundException("You have no investor profile yet"));
        check(membership, needed);
        return membership;
    }

    public static void check(Membership membership, OrgRole needed) {
        if (!membership.orgRole().allows(needed)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Your role in your firm does not allow this");
        }
    }

    /** Writes the owner's implicit membership down. Does nothing for a firm that already has members. */
    void materialiseOwner(UUID investorId) {
        db.update("INSERT INTO investor_members(investor_id, user_id, display_name, org_role)"
                + " SELECT i.id, i.owner_user_id, i.contact_name, 'MANAGING_DIRECTOR' FROM investors i"
                + " WHERE i.id = ? AND i.owner_user_id IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM investor_members m WHERE m.investor_id = i.id)"
                + " ON CONFLICT DO NOTHING", investorId);
    }

    /** The person who creates a firm profile runs it. */
    public void addFounder(UUID investorId, UUID userId, String email, String displayName) {
        db.update("INSERT INTO investor_members(investor_id, user_id, email, display_name, org_role)"
                + " VALUES (?,?,?,?,'MANAGING_DIRECTOR')", investorId, userId, email, displayName);
    }
}
