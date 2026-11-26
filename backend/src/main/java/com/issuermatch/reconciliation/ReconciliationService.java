package com.issuermatch.reconciliation;

import com.issuermatch.common.NotFoundException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares three records that should agree: commitments, each need's remaining capacity, and the event ledger.
 * It reports, it never repairs: a human decides what the truth is.
 * Runs in REPEATABLE READ so all checks see one consistent snapshot, even while commitments keep arriving.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final int MAX_REPORTED = 500;

    public record Mismatch(String type, UUID needId, UUID commitmentId, String detail) {}

    /** mismatches holds at most 500 entries; mismatchCount and countsByType are complete. */
    public record Report(UUID id, Instant startedAt, Instant finishedAt, UUID triggeredBy, int mismatchCount,
                         Map<String, Integer> countsByType, List<Mismatch> mismatches) {}

    private final JdbcTemplate db;
    private final Duration grace;

    /** @param grace how long a commitment may wait for its events before they count as missing (they travel via Kafka) */
    ReconciliationService(JdbcTemplate db, @Value("${app.reconciliation.grace:1m}") Duration grace) {
        this.db = db;
        this.grace = grace;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Report run(UUID triggeredBy) {
        UUID run = db.queryForObject("INSERT INTO reconciliation_runs(triggered_by) VALUES (?) RETURNING id", UUID.class, triggeredBy);
        String into = "INSERT INTO reconciliation_mismatches(run_id, type, need_id, commitment_id, detail) ";

        // 1. Totals: what the need says is gone must equal what live commitments hold.
        db.update(into + "SELECT ?, 'CAPACITY_MISMATCH', n.id, NULL, format('amount %s minus remaining %s is %s, but live commitments hold %s',"
                + " n.amount, n.remaining_capacity, n.amount - n.remaining_capacity, coalesce(s.held, 0))"
                + " FROM funding_needs n LEFT JOIN (SELECT need_id, sum(amount) AS held FROM commitments"
                + " WHERE status IN ('ACCEPTED','PENDING_REVIEW') AND issuer_decision <> 'DECLINED' GROUP BY need_id) s"
                + " ON s.need_id = n.id"
                + " WHERE n.amount - n.remaining_capacity <> coalesce(s.held, 0)", run);

        // 2. Missing events: every commitment needs REQUESTED, plus ACCEPTED or REJECTED once it is decided, plus
        // DECLINED if the issuer turned it down. Each is overdue counted from the change that should have sent it.
        db.update(into + "SELECT ?, 'MISSING_EVENT', c.need_id, c.id, format('no %s event for a %s commitment', e.type, c.status)"
                + " FROM commitments c CROSS JOIN LATERAL (VALUES ('REQUESTED', coalesce(c.reviewed_at, c.created_at)),"
                + " (CASE c.status WHEN 'ACCEPTED' THEN 'ACCEPTED' WHEN 'REJECTED' THEN 'REJECTED' END,"
                + " coalesce(c.reviewed_at, c.created_at)),"
                + " (CASE c.issuer_decision WHEN 'DECLINED' THEN 'DECLINED' END, c.issuer_decided_at)) AS e(type, since)"
                + " WHERE e.type IS NOT NULL AND e.since < now() - make_interval(secs => ?)"
                + " AND NOT EXISTS (SELECT 1 FROM commitment_events ev WHERE ev.commitment_id = c.id AND ev.type = e.type)",
                run, grace.toSeconds());

        // 3. Double counts: the same kind of event recorded twice for one commitment.
        db.update(into + "SELECT ?, 'DUPLICATE_EVENT', (array_agg(need_id))[1], commitment_id, format('%s %s events', count(*), type)"
                + " FROM commitment_events GROUP BY commitment_id, type HAVING count(*) > 1", run);

        // 4. Events that contradict the record: unknown commitment, or an outcome the commitment does not have.
        db.update(into + "SELECT ?, 'UNEXPECTED_EVENT', ev.need_id, ev.commitment_id, CASE WHEN c.id IS NULL"
                + " THEN format('%s event for a commitment that does not exist', ev.type)"
                + " ELSE format('%s event but the commitment is %s', ev.type, c.status) END"
                + " FROM commitment_events ev LEFT JOIN commitments c ON c.id = ev.commitment_id"
                + " WHERE c.id IS NULL OR (ev.type = 'ACCEPTED' AND c.status <> 'ACCEPTED')"
                + " OR (ev.type = 'REJECTED' AND c.status <> 'REJECTED')"
                + " OR (ev.type = 'DECLINED' AND c.issuer_decision <> 'DECLINED')", run);

        // 5. Events whose money does not match the commitment.
        db.update(into + "SELECT DISTINCT ?::uuid, 'AMOUNT_MISMATCH', c.need_id, c.id, format('%s event says %s, commitment says %s',"
                + " ev.type, ev.amount, c.amount) FROM commitment_events ev JOIN commitments c ON c.id = ev.commitment_id"
                + " WHERE ev.amount <> c.amount", run);

        db.update("UPDATE reconciliation_runs SET finished_at = clock_timestamp(), mismatch_count ="
                + " (SELECT count(*) FROM reconciliation_mismatches WHERE run_id = ?) WHERE id = ?", run, run);
        Report report = load(run);
        if (report.mismatchCount() > 0) {
            log.warn("Reconciliation found {} mismatch(es): {}", report.mismatchCount(), report.countsByType());
        }
        return report;
    }

    @Transactional(readOnly = true)
    public Report latest() {
        List<UUID> ids = db.queryForList("SELECT id FROM reconciliation_runs WHERE finished_at IS NOT NULL"
                + " ORDER BY started_at DESC LIMIT 1", UUID.class);
        if (ids.isEmpty()) {
            throw new NotFoundException("No reconciliation has run yet");
        }
        return load(ids.get(0));
    }

    /** Reports are only history: keep a week. */
    @Transactional
    public int removeOlderThan(Duration age) {
        return db.update("DELETE FROM reconciliation_runs WHERE started_at < now() - make_interval(secs => ?)", age.toSeconds());
    }

    private Report load(UUID run) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        db.query("SELECT type, count(*) AS n FROM reconciliation_mismatches WHERE run_id = ? GROUP BY type ORDER BY type",
                rs -> { counts.put(rs.getString("type"), rs.getInt("n")); }, run);
        List<Mismatch> mismatches = db.query("SELECT type, need_id, commitment_id, detail FROM reconciliation_mismatches"
                        + " WHERE run_id = ? ORDER BY type, need_id, commitment_id LIMIT " + MAX_REPORTED,
                (rs, n) -> new Mismatch(rs.getString("type"), rs.getObject("need_id", UUID.class),
                        rs.getObject("commitment_id", UUID.class), rs.getString("detail")), run);
        return db.queryForObject("SELECT started_at, finished_at, triggered_by, mismatch_count FROM reconciliation_runs WHERE id = ?",
                (rs, n) -> {
                    Timestamp finished = rs.getTimestamp("finished_at");
                    return new Report(run, rs.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                            rs.getObject("triggered_by", UUID.class), rs.getInt("mismatch_count"), counts, mismatches);
                }, run);
    }
}
