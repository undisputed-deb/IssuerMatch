package com.issuermatch.matching.scoring;

import com.issuermatch.common.Currencies;
import com.issuermatch.common.Instrument;
import com.issuermatch.common.Region;
import com.issuermatch.common.RiskLevel;
import com.issuermatch.common.Sector;
import com.issuermatch.fundingneed.CompanyStage;
import com.issuermatch.fundingneed.HardConstraints;
import com.issuermatch.investor.InvestorType;
import com.issuermatch.investor.ParticipationRole;
import com.issuermatch.matching.embedding.Embeddings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Reads the facts from the database, converts money to US dollars, and asks FitScorer. */
@Service
public class ScoringService {

    private final JdbcTemplate db;
    private final Embeddings embeddings;
    private final JsonMapper json;

    ScoringService(JdbcTemplate db, Embeddings embeddings, JsonMapper json) {
        this.db = db;
        this.embeddings = embeddings;
        this.json = json;
    }

    /** @throws IllegalArgumentException for an unknown need or investor, or an investor with no mandates */
    public ScoreBreakdown score(UUID needId, UUID investorId) {
        NeedProfile need = need(needId).orElseThrow(() -> new IllegalArgumentException("Unknown funding need"));
        Optional<String> needVector = embeddings.ofNeed(needId);
        return FitScorer.score(need, investor(investorId, need, needVector.orElse(null)));
    }

    public Optional<NeedProfile> need(UUID needId) {
        return db.query("SELECT sector, region, amount, currency, min_ticket_size, instrument, risk_level, company_stage,"
                        + " hard_constraints::text AS constraints FROM funding_needs WHERE id = ?",
                (rs, n) -> {
                    String currency = rs.getString("currency");
                    BigDecimal minTicket = rs.getBigDecimal("min_ticket_size");
                    return new NeedProfile(Sector.valueOf(rs.getString("sector")), Region.valueOf(rs.getString("region")),
                            Currencies.toUsd(rs.getBigDecimal("amount"), currency),
                            minTicket == null ? null : Currencies.toUsd(minTicket, currency),
                            Instrument.valueOf(rs.getString("instrument")), RiskLevel.valueOf(rs.getString("risk_level")),
                            json.readValue(rs.getString("constraints"), HardConstraints.class),
                            rs.getString("company_stage") == null ? null : CompanyStage.valueOf(rs.getString("company_stage")));
                }, needId).stream().findFirst();
    }

    private InvestorFacts investor(UUID investorId, NeedProfile need, String needVector) {
        record Row(InvestorType type, Region region, BigDecimal deployable, String currency, Double similarity,
                   ParticipationRole participation, boolean paused) {}
        List<Row> rows = db.query("SELECT investor_type, region, deployable_capital_usd, currency, participation_role,"
                        + " deployment_status,"
                        + " CASE WHEN thesis_embedding IS NULL OR ?::vector IS NULL THEN NULL"
                        + " ELSE 1 - (thesis_embedding <=> ?::vector) END AS similarity FROM investors WHERE id = ?",
                (rs, n) -> new Row(InvestorType.valueOf(rs.getString("investor_type")), Region.valueOf(rs.getString("region")),
                        rs.getBigDecimal("deployable_capital_usd"), rs.getString("currency"), (Double) rs.getObject("similarity"),
                        rs.getString("participation_role") == null ? null
                                : ParticipationRole.valueOf(rs.getString("participation_role")),
                        "PAUSED".equals(rs.getString("deployment_status"))),
                needVector, needVector, investorId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Unknown investor or investor has no mandates");
        }
        Row row = rows.get(0);
        List<MandateProfile> mandates = db.query(
                "SELECT sector, region, min_ticket, max_ticket, risk_level, instruments, target_stages::text AS stages"
                        + " FROM mandates WHERE investor_id = ?",
                (rs, n) -> new MandateProfile(Sector.valueOf(rs.getString("sector")), Region.valueOf(rs.getString("region")),
                        Currencies.toUsd(rs.getBigDecimal("min_ticket"), row.currency()),
                        Currencies.toUsd(rs.getBigDecimal("max_ticket"), row.currency()),
                        RiskLevel.valueOf(rs.getString("risk_level")),
                        List.of((String[]) rs.getArray("instruments").getArray()),
                        List.of(json.readValue(rs.getString("stages"), String[].class))), investorId);
        if (mandates.isEmpty()) {
            throw new IllegalArgumentException("Unknown investor or investor has no mandates");
        }
        int sectorDeals = db.queryForObject("SELECT count(*) FROM investor_deals WHERE investor_id = ? AND sector = ?",
                Integer.class, investorId, need.sector().name());
        int regionDeals = db.queryForObject("SELECT count(*) FROM investor_deals WHERE investor_id = ? AND region = ?",
                Integer.class, investorId, need.region().name());
        return new InvestorFacts(row.type(), row.region(), row.deployable(), mandates, sectorDeals, regionDeals, row.similarity(),
                row.participation(), row.paused());
    }
}
