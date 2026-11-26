package com.issuermatch.matching;

import com.issuermatch.matching.agent.AgentFailure;
import com.issuermatch.matching.agent.MatchAgent;
import com.issuermatch.matching.agent.ModelOutput;
import com.issuermatch.matching.scoring.ScoreBreakdown;
import com.issuermatch.matching.scoring.ScoringService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Matching without the language model, so a deal is not dropped when the model is down or over budget.
 * Same scorer, same hard constraints; the text comes from templates and says so.
 */
@Component
class DeterministicMatcher {

    private static final int MAX_CANDIDATES = 300;
    private static final int MAX_RESULTS = 10;

    private static final Map<String, List<String>> QUESTIONS = Map.of(
            "EQUITY", List.of("What is current annual recurring revenue and how fast did it grow in the last year?",
                    "How long does the current runway last without this raise?",
                    "What will this capital change in the next 18 months?"),
            "DEBT", List.of("How comfortably does cash flow cover the proposed debt service?",
                    "What collateral is offered and who else has a claim on it?",
                    "What existing debt and covenants does the company have?"),
            "MEZZANINE", List.of("Which senior lenders rank ahead, and for how much?",
                    "How is the mezzanine tranche expected to be repaid?",
                    "How comfortably does cash flow cover all debt service?"),
            "CONVERTIBLE", List.of("How was the valuation cap chosen?",
                    "When is the next priced round expected and what would trigger conversion?",
                    "What happens to the note if no priced round happens before maturity?"));

    private final JdbcTemplate db;
    private final ScoringService scoring;

    DeterministicMatcher(JdbcTemplate db, ScoringService scoring) {
        this.db = db;
        this.scoring = scoring;
    }

    private record Need(String title, String sector, String region, BigDecimal amount, String currency, String instrument) {}

    private record Scored(UUID investorId, ScoreBreakdown score) {}

    MatchAgent.Result match(UUID needId) {
        long start = System.nanoTime();
        Need need = db.query("SELECT title, sector, region, amount, currency, instrument FROM funding_needs WHERE id = ?",
                        (rs, n) -> new Need(rs.getString("title"), rs.getString("sector"), rs.getString("region"),
                                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("instrument")), needId)
                .stream().findFirst().orElseThrow(() -> new AgentFailure("Funding need not found"));
        List<UUID> candidates = db.queryForList("SELECT DISTINCT investor_id FROM mandates WHERE sector = ? LIMIT " + MAX_CANDIDATES,
                UUID.class, need.sector());
        List<ModelOutput.Entry> entries = candidates.stream()
                .map(id -> new Scored(id, scoring.score(needId, id)))
                .filter(s -> s.score().qualified())
                .sorted(Comparator.<Scored, BigDecimal>comparing(s -> s.score().total()).reversed()
                        .thenComparing(s -> s.investorId().toString()))
                .limit(MAX_RESULTS)
                .map(s -> entry(need, s))
                .toList();
        if (entries.isEmpty()) {
            throw new AgentFailure("No qualified investors");
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new MatchAgent.Result(entries, new MatchAgent.Run("", 0, 0, ms, List.of()));
    }

    private static ModelOutput.Entry entry(Need need, Scored s) {
        ScoreBreakdown b = s.score();
        String explanation = "Scored " + b.total().toPlainString() + " of 100 without the language model: mandate "
                + b.mandateFit().toPlainString() + "/25, ticket " + b.ticketFit().toPlainString() + "/25, region "
                + b.regionFit().toPlainString() + "/15, risk " + b.riskFit().toPlainString() + "/10, track record "
                + b.trackRecord().toPlainString() + "/10, thesis " + b.thesisAlignment().toPlainString() + "/15.";
        String brief = "“" + need.title() + "” is raising " + need.amount().toPlainString() + " " + need.currency() + " of "
                + need.instrument().toLowerCase() + " (" + words(need.sector()) + ", " + words(need.region()) + ") and scores "
                + b.total().toPlainString() + " of 100 against your mandate. This summary was generated automatically.";
        return new ModelOutput.Entry(s.investorId(), explanation, List.of("scoreFit"), brief, QUESTIONS.get(need.instrument()));
    }

    private static String words(String value) {
        return value.toLowerCase().replace('_', ' ');
    }
}
