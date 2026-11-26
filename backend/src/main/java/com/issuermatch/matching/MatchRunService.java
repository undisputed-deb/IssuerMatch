package com.issuermatch.matching;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.AppMetrics;
import com.issuermatch.matching.agent.MatchAgent;
import com.issuermatch.matching.agent.PromptInjectionDetector;
import com.issuermatch.matching.agent.MatchingTools;
import com.issuermatch.matching.agent.ModelOutput;
import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists what the agent did. Key rules:
 * - the stored score and the rank come from FitScorer (via the scoreFit tool), never from the model's text;
 * - every tool call, its result, the token usage and the latency are stored with the run (explainability);
 * - results land in PENDING_REVIEW. Nothing is approved without a human.
 */
@Service
public class MatchRunService {

    private static final Logger log = LoggerFactory.getLogger(MatchRunService.class);

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final MatchingTools tools;
    private final OutboxService outbox;
    private final AuditService audit;
    private final AppMetrics metrics;

    MatchRunService(JdbcTemplate db, JsonMapper json, MatchingTools tools, OutboxService outbox, AuditService audit,
                    AppMetrics metrics) {
        this.metrics = metrics;
        this.audit = audit;
        this.db = db;
        this.json = json;
        this.tools = tools;
        this.outbox = outbox;
    }

    /**
     * Atomically take ownership of a run. Exactly one caller wins, however many times the event is delivered.
     * The need id must match the run, so a forged event cannot point a run at another need.
     */
    @Transactional
    public boolean claim(UUID runId, UUID needId) {
        boolean claimed = db.update("UPDATE match_runs SET status = 'RUNNING', started_at = now()"
                        + " WHERE id = ? AND need_id = ? AND status = 'REQUESTED'",
                runId, needId) == 1;
        if (claimed) {
            flagSuspiciousInput(runId, needId);
        }
        return claimed;
    }

    /** Mark instruction-like funding text on the run (kept even if the run fails) and put it in the audit trail. */
    private void flagSuspiciousInput(UUID runId, UUID needId) {
        Map<String, Object> need = db.queryForMap("SELECT title, description FROM funding_needs WHERE id = ?", needId);
        List<String> flags = PromptInjectionDetector.detect((String) need.get("title"), (String) need.get("description"));
        if (flags.isEmpty()) {
            return;
        }
        db.update("UPDATE match_runs SET input_flags = string_to_array(?, ',') WHERE id = ?", String.join(",", flags), runId);
        audit.record("PROMPT_INJECTION_SUSPECTED", "funding-need:" + needId, Map.of("runId", runId.toString(), "rules", flags));
        log.warn("Run {} for need {}: instruction-like input detected {}", runId, needId, flags); // rule names only, never the text
    }

    @Transactional
    public void complete(UUID runId, UUID needId, MatchAgent.Result result) {
        record Scored(ModelOutput.Entry entry, BigDecimal score) {}
        List<Scored> ranked = result.rankings().stream()
                .map(e -> new Scored(e, tools.scoreFit(needId, e.investorId()).total()))
                .sorted(Comparator.comparing(Scored::score).reversed()
                        .thenComparing(s -> s.entry().investorId().toString())) // ties: stable and reproducible
                .toList();

        MatchAgent.Run run = result.run();
        // Only a RUNNING run can complete. If the reaper already failed it, a late finish must not resurrect it.
        int changed = db.update("UPDATE match_runs SET status = 'PENDING_REVIEW', tokens_in = ?, tokens_out = ?,"
                        + " latency_ms = ?, tool_calls = ?::jsonb WHERE id = ? AND status = 'RUNNING'",
                run.tokensIn(), run.tokensOut(), (int) Math.min(run.latencyMs(), Integer.MAX_VALUE),
                json.writeValueAsString(run.toolCalls()), runId);
        if (changed == 0) {
            log.warn("Run {} finished after it was no longer RUNNING (reaped?). Result discarded.", runId);
            return;
        }
        int rank = 1;
        for (Scored s : ranked) {
            db.update("INSERT INTO match_results(run_id, investor_id, rank, score, explanation, cited_tools)"
                            + " VALUES (?,?,?,?,?,?::jsonb)",
                    runId, s.entry().investorId(), rank++, s.score(), s.entry().explanation(),
                    json.writeValueAsString(s.entry().citedTools()));
        }
        announce(runId, needId, "PENDING_REVIEW", ranked.size());
        metrics.matchFinished("PENDING_REVIEW", Duration.ofMillis(run.latencyMs()), run.tokensIn(), run.tokensOut());
    }

    /** @param message safe to store and show (no provider details, no secrets) */
    @Transactional
    public void fail(UUID runId, UUID needId, String message) {
        int changed = db.update("UPDATE match_runs SET status = 'FAILED', error = ? WHERE id = ? AND status = 'RUNNING'",
                message, runId);
        if (changed == 1) {
            releaseNeed(needId);
            announce(runId, needId, "FAILED", 0);
            // a failed run has no reliable token count; its duration is measured from the claim
            Double seconds = db.queryForObject("SELECT extract(epoch FROM clock_timestamp() - coalesce(started_at, clock_timestamp()))"
                    + " FROM match_runs WHERE id = ?", Double.class, runId);
            metrics.matchFinished("FAILED", Duration.ofMillis(seconds == null ? 0 : (long) (seconds * 1000)), 0, 0);
        }
    }

    /**
     * Fail runs that have been RUNNING longer than the limit (the app crashed or restarted mid-run).
     * @return how many runs were failed
     */
    @Transactional
    public int reapStuck(Duration olderThan) {
        List<Map<String, Object>> stuck = db.queryForList(
                "UPDATE match_runs SET status = 'FAILED', error = ? WHERE status = 'RUNNING'"
                        + " AND started_at < now() - make_interval(secs => ?) RETURNING id, need_id",
                "Run abandoned: no result after " + olderThan.toMinutes() + " min", olderThan.toSeconds());
        for (Map<String, Object> r : stuck) {
            releaseNeed((UUID) r.get("need_id"));
            announce((UUID) r.get("id"), (UUID) r.get("need_id"), "FAILED", 0);
        }
        return stuck.size();
    }

    /** A run that failed must not leave its need stuck in MATCHING: back to SUBMITTED, so it can be requested again. */
    private void releaseNeed(UUID needId) {
        db.update("UPDATE funding_needs SET status = 'SUBMITTED', version = version + 1 WHERE id = ? AND status = 'MATCHING'", needId);
    }

    private void announce(UUID runId, UUID needId, String status, int resultCount) {
        outbox.publish(Topics.MATCH_COMPLETED, needId.toString(), "MatchCompleted",
                Map.of("runId", runId.toString(), "needId", needId.toString(), "status", status,
                        "resultCount", resultCount));
    }
}
