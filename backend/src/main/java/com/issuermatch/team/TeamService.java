package com.issuermatch.team;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A firm's roster and invitations. Every change locks the firm's row first and reads the caller's role again afterwards,
 * so two managing directors acting at once cannot leave the firm without one.
 */
@Service
@Transactional
class TeamService {

    static final Duration INVITE_LIFETIME = Duration.ofDays(7);
    private static final String PENDING = " accepted_at IS NULL AND revoked_at IS NULL";

    private final JdbcTemplate db;
    private final InvestorMembershipService memberships;
    private final AuditService audit;

    TeamService(JdbcTemplate db, InvestorMembershipService memberships, AuditService audit) {
        this.db = db;
        this.memberships = memberships;
        this.audit = audit;
    }

    TeamDtos.Team team(UUID userId, String email) {
        var me = memberships.require(userId, OrgRole.VIEWER);
        memberships.materialiseOwner(me.investorId());
        if (email != null) { // owners carried over from before teams have no address on file
            db.update("UPDATE investor_members SET email = ? WHERE user_id = ? AND email IS NULL", email, userId);
        }
        List<TeamDtos.Member> members = db.query("SELECT user_id, email, display_name, org_role, joined_at FROM investor_members"
                        + " WHERE investor_id = ? ORDER BY joined_at, user_id",
                (rs, n) -> new TeamDtos.Member(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                        OrgRole.valueOf(rs.getString("org_role")), rs.getTimestamp("joined_at").toInstant(),
                        userId.equals(rs.getObject("user_id", UUID.class))), me.investorId());
        List<TeamDtos.Invite> invites = me.orgRole() != OrgRole.MANAGING_DIRECTOR ? List.of()
                : db.query("SELECT id, email, org_role, created_at, expires_at FROM investor_invites WHERE investor_id = ? AND"
                        + PENDING + " AND expires_at > clock_timestamp() ORDER BY created_at DESC LIMIT 100",
                (rs, n) -> new TeamDtos.Invite(rs.getObject("id", UUID.class), rs.getString("email"),
                        OrgRole.valueOf(rs.getString("org_role")), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), me.investorId());
        return new TeamDtos.Team(me.investorId(), me.orgRole(), members, invites);
    }

    TeamDtos.CreatedInvite invite(UUID userId, TeamDtos.InviteRequest request) {
        UUID firm = lockFirmAsDirector(userId);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String token = InviteTokens.generate();
        var row = db.queryForMap("INSERT INTO investor_invites(investor_id, email, org_role, token_hash, invited_by, expires_at)"
                        + " VALUES (?,?,?,?,?, clock_timestamp() + make_interval(secs => ?)) RETURNING id, created_at, expires_at",
                firm, email, request.orgRole().name(), InviteTokens.hash(token), userId, INVITE_LIFETIME.toSeconds());
        UUID id = (UUID) row.get("id");
        // ids and role only: never the token, and no address in the audit trail
        audit.record("INVESTOR_INVITE_CREATED", "investor:" + firm, Map.of("inviteId", id.toString(), "orgRole", request.orgRole().name()));
        return new TeamDtos.CreatedInvite(id, email, request.orgRole(), ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                ((java.sql.Timestamp) row.get("expires_at")).toInstant(), "/invite/" + token);
    }

    void revokeInvite(UUID userId, UUID inviteId) {
        UUID firm = lockFirmAsDirector(userId);
        if (db.update("UPDATE investor_invites SET revoked_at = clock_timestamp() WHERE id = ? AND investor_id = ? AND" + PENDING,
                inviteId, firm) == 0) {
            if (db.queryForList("SELECT id FROM investor_invites WHERE id = ? AND investor_id = ?", UUID.class, inviteId, firm).isEmpty()) {
                throw new NotFoundException("Invite not found"); // another firm's invite looks like a missing one
            }
            throw new ApiException(HttpStatus.CONFLICT, "This invite was already used or revoked");
        }
        audit.record("INVESTOR_INVITE_REVOKED", "investor:" + firm, Map.of("inviteId", inviteId.toString()));
    }

    void changeRole(UUID userId, UUID memberId, OrgRole role) {
        UUID firm = lockFirmAsDirector(userId);
        OrgRole current = roleOf(firm, memberId);
        if (role != OrgRole.MANAGING_DIRECTOR) {
            keepOneDirector(firm, current);
            revokeInvitesBy(firm, memberId);
        }
        db.update("UPDATE investor_members SET org_role = ? WHERE investor_id = ? AND user_id = ?", role.name(), firm, memberId);
        audit.record("INVESTOR_MEMBER_ROLE_CHANGED", "investor:" + firm,
                Map.of("userId", memberId.toString(), "from", current.name(), "to", role.name()));
    }

    void remove(UUID userId, UUID memberId) {
        UUID firm = lockFirmAsDirector(userId);
        OrgRole current = roleOf(firm, memberId);
        keepOneDirector(firm, current);
        revokeInvitesBy(firm, memberId);
        db.update("DELETE FROM investor_members WHERE investor_id = ? AND user_id = ?", firm, memberId);
        audit.record("INVESTOR_MEMBER_REMOVED", "investor:" + firm, Map.of("userId", memberId.toString()));
    }

    private UUID lockFirmAsDirector(UUID userId) {
        UUID firm = memberships.require(userId, OrgRole.MANAGING_DIRECTOR).investorId();
        db.queryForList("SELECT id FROM investors WHERE id = ? FOR NO KEY UPDATE", UUID.class, firm);
        // read again under the lock: someone may have demoted or removed the caller while this request waited
        memberships.require(userId, OrgRole.MANAGING_DIRECTOR);
        memberships.materialiseOwner(firm);
        return firm;
    }

    private OrgRole roleOf(UUID firm, UUID memberId) {
        return db.queryForList("SELECT org_role FROM investor_members WHERE investor_id = ? AND user_id = ?", String.class, firm, memberId)
                .stream().findFirst().map(OrgRole::valueOf).orElseThrow(() -> new NotFoundException("Member not found"));
    }

    private void keepOneDirector(UUID firm, OrgRole leaving) {
        if (leaving == OrgRole.MANAGING_DIRECTOR && db.queryForObject(
                "SELECT count(*) FROM investor_members WHERE investor_id = ? AND org_role = 'MANAGING_DIRECTOR'", Integer.class, firm) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "A firm needs at least one Managing Director");
        }
    }

    /** Someone who loses the right to invite takes their open invitations with them. */
    private void revokeInvitesBy(UUID firm, UUID memberId) {
        db.update("UPDATE investor_invites SET revoked_at = clock_timestamp() WHERE investor_id = ? AND invited_by = ? AND" + PENDING,
                firm, memberId);
    }
}
