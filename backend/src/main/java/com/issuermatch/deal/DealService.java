package com.issuermatch.deal;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import com.issuermatch.matching.MatchVisibility;
import com.issuermatch.security.CurrentUser;
import com.issuermatch.security.Role;
import com.issuermatch.team.InvestorMembershipService;
import com.issuermatch.team.OrgRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens on a deal after matching: offers are listed, investors pass, and each matched investor has a private
 * question thread with the issuer. Offers themselves are created by CommitmentService, so every safeguard applies.
 * Anyone without a claim on a deal gets 404, the same as for a deal that does not exist.
 */
@Service
@Transactional
class DealService {

    static final int MAX_QUESTIONS_PER_THREAD = 50;

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final AuditService audit;
    private final MatchVisibility visibility;
    private final InvestorMembershipService memberships;

    DealService(JdbcTemplate db, JsonMapper json, AuditService audit, MatchVisibility visibility,
                InvestorMembershipService memberships) {
        this.memberships = memberships;
        this.db = db;
        this.json = json;
        this.audit = audit;
        this.visibility = visibility;
    }

    // ---- offers ----

    /** Staff see every commitment on the need. The owning issuer does not see the ones risk rules or a reviewer rejected. */
    @Transactional(readOnly = true)
    List<DealDtos.Offer> offers(UUID needId, CurrentUser me) {
        if (me.isStaff()) {
            requireNeed(needId);
        } else {
            requireOwner(needId, me.id());
        }
        // ponytail: newest 200, add paging when a raise ever gets more offers than that
        // the investor columns listed here are everything a company learns about a firm that offers
        return db.query("SELECT c.id, c.investor_id, i.name, i.investor_type, i.contact_name, i.role_title, c.amount, n.currency,"
                        + " c.status, c.proposed_stake_pct, c.proposed_terms_json::text AS terms, c.investment_rationale, c.created_at,"
                        + " c.issuer_decision, i.aum_or_revenue_usd, i.deployable_capital_usd, i.website_url, i.verified"
                        + " FROM commitments c JOIN investors i ON i.id = c.investor_id JOIN funding_needs n ON n.id = c.need_id"
                        + " WHERE c.need_id = ? AND (? OR c.status <> 'REJECTED') ORDER BY c.created_at DESC LIMIT 200",
                (rs, k) -> new DealDtos.Offer(uuid(rs, "id"), uuid(rs, "investor_id"), rs.getString("name"),
                        rs.getString("investor_type"), rs.getString("contact_name"), rs.getString("role_title"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
                        rs.getBigDecimal("proposed_stake_pct"), tree(rs.getString("terms")),
                        rs.getString("investment_rationale"), instant(rs, "created_at"), rs.getString("issuer_decision"),
                        rs.getBigDecimal("aum_or_revenue_usd"), rs.getBigDecimal("deployable_capital_usd"),
                        rs.getString("website_url"), rs.getBoolean("verified")),
                needId, me.isStaff());
    }

    @Transactional(readOnly = true)
    List<DealDtos.MyOffer> myOffers(UUID userId) {
        return db.query("SELECT c.id, c.need_id, n.title, c.amount, n.currency, c.status, c.proposed_stake_pct,"
                        + " c.proposed_terms_json::text AS terms, c.investment_rationale, c.created_at, c.issuer_decision"
                        + " FROM commitments c"
                        + " JOIN funding_needs n ON n.id = c.need_id"
                        + " WHERE c.investor_id = ? ORDER BY c.created_at DESC LIMIT 200",
                (rs, k) -> new DealDtos.MyOffer(uuid(rs, "id"), uuid(rs, "need_id"), rs.getString("title"),
                        rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"),
                        rs.getBigDecimal("proposed_stake_pct"), tree(rs.getString("terms")),
                        rs.getString("investment_rationale"), instant(rs, "created_at"), rs.getString("issuer_decision")), firmOf(userId));
    }

    /** "Not for us", with an optional reason. Not possible while an offer is live: that money is already reserved. */
    void pass(UUID matchResultId, UUID userId, String reason) {
        var me = memberships.find(userId);
        var rows = me.isEmpty() ? List.<Map<String, Object>>of()
                : db.queryForList("SELECT m.need_id, r.investor_id FROM match_results r JOIN match_runs m ON m.id = r.run_id"
                        + " WHERE r.id = ? AND m.status = 'APPROVED' AND r.investor_id = ?", matchResultId, me.get().investorId());
        if (rows.isEmpty()) {
            throw new NotFoundException("Match not found");
        }
        InvestorMembershipService.check(me.get(), OrgRole.ANALYST);
        UUID needId = (UUID) rows.get(0).get("need_id");
        UUID investorId = (UUID) rows.get(0).get("investor_id");
        lockInvestor(investorId); // the same lock an offer takes, so a pass and an offer cannot interleave
        if (count("SELECT count(*) FROM commitments WHERE need_id = ? AND investor_id = ? AND status IN ('ACCEPTED','PENDING_REVIEW')"
                + " AND issuer_decision <> 'DECLINED'", needId, investorId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "You have a live offer on this deal, so you cannot pass on it");
        }
        db.update("UPDATE match_results r SET investor_decision = 'PASSED', pass_reason = ?, decided_at = now() FROM match_runs m"
                + " WHERE m.id = r.run_id AND m.need_id = ? AND r.investor_id = ? AND m.status = 'APPROVED'",
                reason == null || reason.isBlank() ? null : reason.strip(), needId, investorId);
        audit.record("DEAL_PASSED", "match-result:" + matchResultId, ids(needId, investorId));
    }

    // ---- questions ----

    @Transactional(readOnly = true)
    List<DealDtos.Question> questions(UUID needId, CurrentUser me) {
        UUID onlyInvestor = null; // staff and the owning issuer see every thread
        if (me.isStaff()) {
            requireNeed(needId);
        } else if (!(me.has(Role.ISSUER) && owns(needId, me.id()))) {
            onlyInvestor = me.has(Role.INVESTOR) ? visibility.matchedFirm(me.id(), needId)
                    .map(InvestorMembershipService.Membership::investorId).orElse(null) : null;
            if (onlyInvestor == null) {
                throw new NotFoundException("Funding need not found");
            }
        }
        String sql = QUESTION + " WHERE q.need_id = ?" + (onlyInvestor == null ? "" : " AND q.investor_id = ?")
                + " ORDER BY q.created_at, q.id LIMIT 500";
        Object[] args = onlyInvestor == null ? new Object[] {needId} : new Object[] {needId, onlyInvestor};
        return db.query(sql, (rs, k) -> question(rs), args);
    }

    /** Every thread of the caller's own firm, so the deal feed makes one call instead of one per deal. */
    @Transactional(readOnly = true)
    List<DealDtos.Question> myQuestions(UUID userId) {
        // ponytail: no paging, bounded by 50 questions per matched need; add paging if a firm matches hundreds of deals
        return db.query(QUESTION + " WHERE q.investor_id = ? ORDER BY q.created_at, q.id", (rs, k) -> question(rs), firmOf(userId));
    }

    DealDtos.Question ask(UUID needId, UUID userId, String text) {
        var me = visibility.matchedFirm(userId, needId).orElseThrow(() -> new NotFoundException("Funding need not found"));
        InvestorMembershipService.check(me, OrgRole.ANALYST);
        UUID investorId = me.investorId();
        lockInvestor(investorId); // so parallel requests cannot slip past the limit
        if (count("SELECT count(*) FROM deal_questions WHERE need_id = ? AND investor_id = ?", needId, investorId)
                >= MAX_QUESTIONS_PER_THREAD) {
            throw new ApiException(HttpStatus.CONFLICT, "This thread reached its limit of " + MAX_QUESTIONS_PER_THREAD + " questions");
        }
        UUID id = db.queryForObject("INSERT INTO deal_questions(need_id, investor_id, asked_by, question) VALUES (?,?,?,?) RETURNING id",
                UUID.class, needId, investorId, userId, text.strip());
        audit.record("DEAL_QUESTION_ASKED", "deal-question:" + id, ids(needId, investorId)); // ids only, never the text
        return byId(id);
    }

    DealDtos.Question answer(UUID needId, UUID questionId, UUID userId, String text) {
        requireOwner(needId, userId);
        List<UUID> asker = db.queryForList("SELECT investor_id FROM deal_questions WHERE id = ? AND need_id = ?", UUID.class,
                questionId, needId);
        if (asker.isEmpty()) {
            throw new NotFoundException("Question not found");
        }
        // conditional update: two answers at once cannot both win
        if (db.update("UPDATE deal_questions SET answer = ?, answered_by = ?, answered_at = clock_timestamp()"
                + " WHERE id = ? AND answer IS NULL", text.strip(), userId, questionId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This question has already been answered");
        }
        audit.record("DEAL_QUESTION_ANSWERED", "deal-question:" + questionId, ids(needId, asker.get(0)));
        return byId(questionId);
    }

    // ---- who may touch a deal ----

    private void requireNeed(UUID needId) {
        if (count("SELECT count(*) FROM funding_needs WHERE id = ?", needId) == 0) {
            throw new NotFoundException("Funding need not found");
        }
    }

    private void requireOwner(UUID needId, UUID userId) {
        if (!owns(needId, userId)) {
            throw new NotFoundException("Funding need not found");
        }
    }

    private boolean owns(UUID needId, UUID userId) {
        return count("SELECT count(*) FROM funding_needs n JOIN issuers s ON s.id = n.issuer_id WHERE n.id = ? AND s.owner_user_id = ?",
                needId, userId) > 0;
    }

    /** The caller's firm, or null (which matches no row) for a user without one. */
    private UUID firmOf(UUID userId) {
        return memberships.find(userId).map(InvestorMembershipService.Membership::investorId).orElse(null);
    }

    private void lockInvestor(UUID investorId) {
        db.queryForList("SELECT id FROM investors WHERE id = ? FOR NO KEY UPDATE", UUID.class, investorId);
    }

    // ---- mapping ----

    private static final String QUESTION = "SELECT q.id, q.need_id, q.investor_id, i.name, q.question, q.answer, q.created_at,"
            + " q.answered_at FROM deal_questions q JOIN investors i ON i.id = q.investor_id";

    private DealDtos.Question byId(UUID id) {
        return db.queryForObject(QUESTION + " WHERE q.id = ?", (rs, k) -> question(rs), id);
    }

    private static DealDtos.Question question(ResultSet rs) throws SQLException {
        return new DealDtos.Question(uuid(rs, "id"), uuid(rs, "need_id"), uuid(rs, "investor_id"), rs.getString("name"),
                rs.getString("question"), rs.getString("answer"), instant(rs, "created_at"), instant(rs, "answered_at"));
    }

    private static Map<String, Object> ids(UUID needId, UUID investorId) {
        return Map.of("needId", needId.toString(), "investorId", investorId.toString());
    }

    private int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private JsonNode tree(String value) {
        return value == null ? null : json.readTree(value);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
