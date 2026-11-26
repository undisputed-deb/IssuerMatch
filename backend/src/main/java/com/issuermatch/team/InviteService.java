package com.issuermatch.team;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The receiving side of an invitation link: look at it, and join with it. */
@Service
@Transactional
public class InviteService {

    private final JdbcTemplate db;
    private final InvestorMembershipService memberships;
    private final AuditService audit;

    InviteService(JdbcTemplate db, InvestorMembershipService memberships, AuditService audit) {
        this.db = db;
        this.memberships = memberships;
        this.audit = audit;
    }

    private record Row(UUID id, UUID investorId, String firmName, String email, OrgRole orgRole, Instant expiresAt,
                       boolean accepted, boolean revoked, boolean expired) {
        boolean usable() {
            return !accepted && !revoked && !expired;
        }
    }

    @Transactional(readOnly = true)
    TeamDtos.Preview preview(String token) {
        Row invite = load(token, "").filter(Row::usable).orElseThrow(InviteService::invalid);
        return new TeamDtos.Preview(invite.firmName(), invite.orgRole(), mask(invite.email()), invite.expiresAt(), "PENDING");
    }

    /** For signup through a link: refuse before an account exists if it could never join. */
    @Transactional(readOnly = true)
    public void requireInvited(String token, String email) {
        Row invite = load(token, "").filter(Row::usable).orElseThrow(InviteService::invalid);
        requireSameAddress(invite, email);
    }

    TeamDtos.Joined accept(String token, UUID userId, String email, String displayName) {
        // the lock makes one link join one person, however many requests arrive together
        Row invite = load(token, " FOR UPDATE OF v").orElseThrow(InviteService::invalid);
        if (invite.revoked() || invite.expired()) {
            throw invalid();
        }
        if (invite.accepted()) {
            throw new ApiException(HttpStatus.CONFLICT, "This invite has already been used");
        }
        requireSameAddress(invite, email);
        if (memberships.find(userId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "You already belong to a firm");
        }
        // investor_members.user_id is unique: two links accepted at once still give one membership
        db.update("INSERT INTO investor_members(investor_id, user_id, email, display_name, org_role) VALUES (?,?,?,?,?)",
                invite.investorId(), userId, invite.email(), displayName, invite.orgRole().name());
        db.update("UPDATE investor_invites SET accepted_at = clock_timestamp(), accepted_by = ? WHERE id = ?", userId, invite.id());
        audit.record("INVESTOR_INVITE_ACCEPTED", "investor:" + invite.investorId(),
                Map.of("inviteId", invite.id().toString(), "orgRole", invite.orgRole().name()));
        return new TeamDtos.Joined(invite.investorId(), invite.firmName(), invite.orgRole());
    }

    private java.util.Optional<Row> load(String token, String lock) {
        if (!InviteTokens.looksValid(token)) {
            return java.util.Optional.empty();
        }
        List<Row> rows = db.query("SELECT v.id, v.investor_id, i.name, v.email, v.org_role, v.expires_at,"
                        + " v.accepted_at IS NOT NULL AS accepted, v.revoked_at IS NOT NULL AS revoked,"
                        + " v.expires_at <= clock_timestamp() AS expired"
                        + " FROM investor_invites v JOIN investors i ON i.id = v.investor_id WHERE v.token_hash = ?" + lock,
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("investor_id", UUID.class), rs.getString("name"),
                        rs.getString("email"), OrgRole.valueOf(rs.getString("org_role")), rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("accepted"), rs.getBoolean("revoked"), rs.getBoolean("expired")),
                InviteTokens.hash(token));
        return rows.stream().findFirst();
    }

    private static void requireSameAddress(Row invite, String email) {
        if (email == null || !invite.email().equalsIgnoreCase(email.strip())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This invite was sent to a different email address");
        }
    }

    private static NotFoundException invalid() {
        return new NotFoundException("This invite link is not valid");
    }

    /** "ivy.chen@fund.test" becomes "i***@fund.test": enough to recognise your own address. */
    static String mask(String email) {
        int at = email.lastIndexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
