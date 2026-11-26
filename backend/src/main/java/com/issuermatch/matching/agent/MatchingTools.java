package com.issuermatch.matching.agent;

import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import com.issuermatch.matching.scoring.FitScorer;
import com.issuermatch.matching.scoring.MandateProfile;
import com.issuermatch.matching.scoring.NeedProfile;
import com.issuermatch.matching.scoring.ScoreBreakdown;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    public record InvestorHit(UUID investorId, String name, String investorType, BigDecimal minTicket,
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
                "SELECT i.id, i.name, i.investor_type, m.min_ticket, m.max_ticket, m.risk_level, m.instruments"
                        + " FROM mandates m JOIN investors i ON i.id = m.investor_id"
                        + " WHERE m.sector = ? AND m.region = ? AND m.min_ticket <= ? AND m.max_ticket >= ?"
                        + " ORDER BY i.name, m.min_ticket LIMIT 100",
                (rs, n) -> new InvestorHit(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("investor_type"),
                        rs.getBigDecimal("min_ticket"), rs.getBigDecimal("max_ticket"), rs.getString("risk_level"),
                        List.of((String[]) rs.getArray("instruments").getArray())),
                sector.name(), region.name(), ticketSize, ticketSize);
        // an investor with two matching mandates is still one candidate
        return rows.stream().filter(distinctBy(InvestorHit::investorId)).limit(MAX_RESULTS).toList();
    }

    public record Deal(String issuerName, String sector, String region, BigDecimal amount, String instrument,
                       LocalDate closedOn) {}

    @Tool(description = "Get an investor's most recent past deals (up to 10), newest first. "
            + "Use it to see whether the investor has real track record in a sector or region.")
    public List<Deal> getInvestorHistory(@ToolParam(description = "Investor id from a search result") UUID investorId) {
        if (investorId == null) {
            throw new IllegalArgumentException("investorId is required");
        }
        Integer exists = db.queryForObject("SELECT count(*) FROM investors WHERE id = ?", Integer.class, investorId);
        if (exists == null || exists == 0) {
            throw new IllegalArgumentException("Unknown investor");
        }
        return db.query("SELECT issuer_name, sector, region, amount, instrument, closed_on FROM investor_deals"
                        + " WHERE investor_id = ? ORDER BY closed_on DESC LIMIT 10",
                (rs, n) -> new Deal(rs.getString("issuer_name"), rs.getString("sector"), rs.getString("region"),
                        rs.getBigDecimal("amount"), rs.getString("instrument"), rs.getObject("closed_on", LocalDate.class)),
                investorId);
    }

    public record MarketNote(Sector sector, String outlook, String note) {}

    // Static, invented notes. A real system would call a market-data service here.
    private static final java.util.Map<Sector, MarketNote> MARKET = java.util.Map.of(
            Sector.TECHNOLOGY, new MarketNote(Sector.TECHNOLOGY, "POSITIVE", "Strong appetite for growth rounds; valuations rising."),
            Sector.FINTECH, new MarketNote(Sector.FINTECH, "NEUTRAL", "Investors favour profitable models over pure growth."),
            Sector.HEALTHCARE, new MarketNote(Sector.HEALTHCARE, "POSITIVE", "Steady demand, long horizons, regulatory risk matters."),
            Sector.ENERGY, new MarketNote(Sector.ENERGY, "POSITIVE", "Transition projects attract debt and infrastructure capital."),
            Sector.REAL_ESTATE, new MarketNote(Sector.REAL_ESTATE, "CAUTIOUS", "Higher rates are slowing deals; debt terms are tighter."),
            Sector.CONSUMER, new MarketNote(Sector.CONSUMER, "NEUTRAL", "Selective: brands with repeat revenue raise faster."),
            Sector.INDUSTRIALS, new MarketNote(Sector.INDUSTRIALS, "NEUTRAL", "Capex-heavy raises favour secured debt and mezzanine."));

    @Tool(description = "Get short market notes and outlook for a sector. Static background context, not a price feed.")
    public MarketNote getMarketContext(@ToolParam(description = "Sector to describe") Sector sector) {
        if (sector == null) {
            throw new IllegalArgumentException("sector is required");
        }
        return MARKET.get(sector);
    }

    public record FitResult(UUID needId, UUID investorId, BigDecimal total, BigDecimal sectorPoints,
                            BigDecimal regionPoints, BigDecimal ticketPoints, BigDecimal riskPoints,
                            BigDecimal instrumentPoints) {}

    @Tool(description = "Compute the official fit score (0-100) of one investor for one funding need, with points per "
            + "criterion. This number is the ranking: do not invent or adjust scores yourself.")
    public FitResult scoreFit(@ToolParam(description = "Funding need id") UUID needId,
                              @ToolParam(description = "Investor id from a search result") UUID investorId) {
        if (needId == null || investorId == null) {
            throw new IllegalArgumentException("needId and investorId are required");
        }
        List<NeedProfile> need = db.query(
                "SELECT sector, region, amount, instrument, risk_level FROM funding_needs WHERE id = ?",
                (rs, n) -> new NeedProfile(Sector.valueOf(rs.getString("sector")), Region.valueOf(rs.getString("region")),
                        rs.getBigDecimal("amount"), Instrument.valueOf(rs.getString("instrument")),
                        RiskLevel.valueOf(rs.getString("risk_level"))), needId);
        if (need.isEmpty()) {
            throw new IllegalArgumentException("Unknown funding need");
        }
        List<MandateProfile> mandates = db.query(
                "SELECT sector, region, min_ticket, max_ticket, risk_level, instruments FROM mandates WHERE investor_id = ?",
                (rs, n) -> new MandateProfile(Sector.valueOf(rs.getString("sector")), Region.valueOf(rs.getString("region")),
                        rs.getBigDecimal("min_ticket"), rs.getBigDecimal("max_ticket"),
                        RiskLevel.valueOf(rs.getString("risk_level")),
                        List.of((String[]) rs.getArray("instruments").getArray())), investorId);
        ScoreBreakdown b = FitScorer.best(need.get(0), mandates)
                .orElseThrow(() -> new IllegalArgumentException("Unknown investor or investor has no mandates"));
        return new FitResult(needId, investorId, b.total(), b.sector(), b.region(), b.ticket(), b.risk(), b.instrument());
    }

    private static <T> java.util.function.Predicate<T> distinctBy(java.util.function.Function<T, Object> key) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        return t -> seen.add(key.apply(t));
    }
}
