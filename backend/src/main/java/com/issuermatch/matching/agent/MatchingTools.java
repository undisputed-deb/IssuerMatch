package com.issuermatch.matching.agent;

import com.issuermatch.common.Region;
import com.issuermatch.common.Sector;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Everything the agent is allowed to do. Every method is READ ONLY: the agent can look things up,
 * it can never write, send or change anything. Results are small records so they can be stored
 * with the run for explainability.
 */
@Component
public class MatchingTools {

    static final int MAX_RESULTS = 20;

    private final JdbcTemplate db;

    MatchingTools(JdbcTemplate db) {
        this.db = db;
    }

    public record InvestorHit(UUID investorId, String name, String type, BigDecimal minTicket,
                              BigDecimal maxTicket, String riskLevel, List<String> instruments) {}

    @Tool(description = "Find investors that have a mandate for the given sector and region whose ticket range "
            + "contains the given ticket size. Returns at most 20 investors, each with its matching mandate.")
    public List<InvestorHit> searchInvestorsByMandate(
            @ToolParam(description = "Funding need sector") Sector sector,
            @ToolParam(description = "Funding need region") Region region,
            @ToolParam(description = "Amount being raised, in the same currency as mandate tickets") BigDecimal ticketSize) {
        if (sector == null || region == null || ticketSize == null || ticketSize.signum() <= 0) {
            throw new IllegalArgumentException("sector, region and a positive ticketSize are required");
        }
        // Uses idx_mandates_match: equality on sector+region first, then the ticket range.
        List<InvestorHit> rows = db.query(
                "SELECT i.id, i.name, i.type, m.min_ticket, m.max_ticket, m.risk_level, m.instruments"
                        + " FROM mandates m JOIN investors i ON i.id = m.investor_id"
                        + " WHERE m.sector = ? AND m.region = ? AND m.min_ticket <= ? AND m.max_ticket >= ?"
                        + " ORDER BY i.name, m.min_ticket LIMIT 100",
                (rs, n) -> new InvestorHit(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("type"),
                        rs.getBigDecimal("min_ticket"), rs.getBigDecimal("max_ticket"), rs.getString("risk_level"),
                        List.of((String[]) rs.getArray("instruments").getArray())),
                sector.name(), region.name(), ticketSize, ticketSize);
        // an investor with two matching mandates is still one candidate
        return rows.stream().filter(distinctBy(InvestorHit::investorId)).limit(MAX_RESULTS).toList();
    }

    private static <T> java.util.function.Predicate<T> distinctBy(java.util.function.Function<T, Object> key) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        return t -> seen.add(key.apply(t));
    }
}
