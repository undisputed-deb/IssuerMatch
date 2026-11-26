package com.issuermatch.matching;

import com.issuermatch.team.InvestorMembershipService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one investor sees about its matches. Deliberately NOT included: the analyst explanation, the tool calls and the
 * rank, because they can describe other investors.
 */
@Component
class InvestorFeed {

    record Deal(UUID matchResultId, UUID needId, UUID investorId, String issuerName, String title, String description,
                String sector, String region, BigDecimal amount, String currency, String instrument, String companyStage,
                LocalDate targetCloseDate, JsonNode useOfFunds, JsonNode instrumentTerms, BigDecimal minTicketSize,
                BigDecimal remainingCapacity, BigDecimal score, JsonNode scoreBreakdown, String dealBrief,
                JsonNode suggestedDiligenceQuestions, Instant approvedAt, String investorDecision, String passReason,
                Company company) {}

    /** The company profile, data room and runway included. Only reachable through this feed: approved matches. */
    record Company(boolean verified, String contactName, String contactRole, String websiteUrl, String summary,
                   Integer foundedYear, String teamSizeBand, BigDecimal totalRaisedUsd, Integer runwayMonths,
                   String dataRoomUrl) {}

    private final JdbcTemplate db;
    private final JsonMapper json;

    private final InvestorMembershipService memberships;

    InvestorFeed(JdbcTemplate db, JsonMapper json, InvestorMembershipService memberships) {
        this.memberships = memberships;
        this.db = db;
        this.json = json;
    }

    /** One entry per funding need (the latest approved match), newest approval first. Every member of a firm sees the same. */
    List<Deal> of(UUID userId) {
        UUID firm = memberships.find(userId).map(InvestorMembershipService.Membership::investorId).orElse(null);
        if (firm == null) {
            return List.of();
        }
        List<Deal> deals = db.query("SELECT DISTINCT ON (m.need_id) r.id AS result_id, m.need_id, r.investor_id, s.name AS issuer_name,"
                        + " n.title, n.description, n.sector, n.region, n.amount, n.currency, n.instrument, n.company_stage,"
                        + " n.target_close_date, n.use_of_funds::text AS use_of_funds, n.instrument_terms::text AS terms,"
                        + " n.min_ticket_size, n.remaining_capacity, r.score, r.score_breakdown::text AS breakdown, r.deal_brief,"
                        + " r.suggested_diligence_questions::text AS questions, m.reviewed_at, r.investor_decision,"
                        + " r.pass_reason, s.verified, s.contact_name, s.contact_role, s.website_url, s.company_summary,"
                        + " s.founded_year, s.team_size_band, s.total_raised_usd, s.runway_months, s.data_room_url"
                        + " FROM match_results r JOIN match_runs m ON m.id = r.run_id JOIN funding_needs n ON n.id = m.need_id"
                        + " JOIN issuers s ON s.id = n.issuer_id"
                        + " WHERE m.status = 'APPROVED' AND r.investor_id = ?"
                        + " ORDER BY m.need_id, m.reviewed_at DESC NULLS LAST",
                (rs, k) -> new Deal(rs.getObject("result_id", UUID.class), rs.getObject("need_id", UUID.class),
                        rs.getObject("investor_id", UUID.class), rs.getString("issuer_name"), rs.getString("title"),
                        rs.getString("description"), rs.getString("sector"), rs.getString("region"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("instrument"), rs.getString("company_stage"),
                        rs.getObject("target_close_date", LocalDate.class), tree(rs.getString("use_of_funds")),
                        tree(rs.getString("terms")), rs.getBigDecimal("min_ticket_size"), rs.getBigDecimal("remaining_capacity"),
                        rs.getBigDecimal("score"), tree(rs.getString("breakdown")), rs.getString("deal_brief"),
                        tree(rs.getString("questions")),
                        rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                        rs.getString("investor_decision"), rs.getString("pass_reason"),
                        new Company(rs.getBoolean("verified"), rs.getString("contact_name"), rs.getString("contact_role"),
                                rs.getString("website_url"), rs.getString("company_summary"),
                                (Integer) rs.getObject("founded_year"), rs.getString("team_size_band"),
                                rs.getBigDecimal("total_raised_usd"), (Integer) rs.getObject("runway_months"),
                                rs.getString("data_room_url"))), firm);
        return deals.stream().sorted(Comparator.comparing(Deal::approvedAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    private JsonNode tree(String value) {
        return value == null ? null : json.readTree(value);
    }
}
