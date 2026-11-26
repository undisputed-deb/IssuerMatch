package com.issuermatch.commitment;

import com.issuermatch.audit.AuditService;
import com.issuermatch.commitment.CommitmentDtos.CreateRequest;
import com.issuermatch.commitment.CommitmentDtos.DecisionView;
import com.issuermatch.commitment.CommitmentDtos.Response;
import com.issuermatch.commitment.IdempotencyService.Saved;
import com.issuermatch.commitment.risk.RiskContext;
import com.issuermatch.commitment.risk.RiskEngine;
import com.issuermatch.commitment.risk.RiskFacts;
import com.issuermatch.commitment.risk.Verdict;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.AppMetrics;
import com.issuermatch.common.NotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class CommitmentService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final JdbcTemplate db;
    private final IdempotencyService idempotency;
    private final JsonMapper json;
    private final AuditService audit;
    private final RiskEngine risk;
    private final CommitmentEvents events;
    private final AppMetrics metrics;

    CommitmentService(JdbcTemplate db, IdempotencyService idempotency, JsonMapper json, AuditService audit, RiskEngine risk,
                      CommitmentEvents events, AppMetrics metrics) {
        this.metrics = metrics;
        this.events = events;
        this.db = db;
        this.idempotency = idempotency;
        this.json = json;
        this.audit = audit;
        this.risk = risk;
    }

    /** Idempotent: the whole thing (key + commitment + decisions + capacity) is one transaction. */
    @Transactional
    Saved create(UUID actor, String idempotencyKey, CreateRequest request) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required (1-128 characters: letters, digits, . _ : -)");
        }
        return idempotency.execute(actor, idempotencyKey, sha256(request.canonical()), () -> doCreate(actor, request));
    }

    private Saved doCreate(UUID actor, CreateRequest r) {
        // Lock the need's row first. From here until commit nobody else can commit to this need, so every rule
        // sees the same capacity and exposure numbers we do. Concurrent commitments to one need queue up.
        List<NeedRow> needs = db.query("SELECT issuer_id, remaining_capacity FROM funding_needs WHERE id = ? FOR UPDATE",
                (rs, n) -> new NeedRow(rs.getObject("issuer_id", UUID.class), rs.getBigDecimal("remaining_capacity")), r.needId());
        if (needs.isEmpty()) {
            throw new NotFoundException("Funding need not found");
        }
        NeedRow need = needs.get(0);
        // Then lock the investor's row. Velocity, duplicate and concentration count this investor's history across ALL
        // needs, so two requests for different needs must also queue. Lock order is always need, then investor: a
        // request holding an investor lock never waits for another need, so no cycle (deadlock) is possible.
        // NO KEY UPDATE: does not block the foreign-key checks of unrelated inserts that point at this investor.
        if (db.queryForList("SELECT id FROM investors WHERE id = ? FOR NO KEY UPDATE", UUID.class, r.investorId()).isEmpty()) {
            throw new NotFoundException("Investor not found");
        }
        // Only an investor an analyst approved for this need may commit to it.
        if (count("SELECT count(*) FROM match_results r JOIN match_runs m ON m.id = r.run_id"
                + " WHERE m.need_id = ? AND r.investor_id = ? AND m.status = 'APPROVED'", r.needId(), r.investorId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Investor is not an approved match for this funding need");
        }

        RiskEngine.Outcome outcome = risk.evaluate(new RiskContext(r.needId(), r.investorId(), r.amount(),
                need.remaining(), mandateRanges(r), new Facts(r, need)));
        String status = switch (outcome.verdict()) {
            case PASS -> "ACCEPTED";
            case FLAG -> "PENDING_REVIEW";
            case BLOCK -> "REJECTED";
        };
        // ACCEPTED and PENDING_REVIEW both hold capacity (a flagged commitment must not be overtaken while it waits).
        BigDecimal remaining = need.remaining();
        if (outcome.verdict() != Verdict.BLOCK) {
            remaining = takeCapacity(r.needId(), r.amount());
        }

        var created = db.queryForMap("INSERT INTO commitments(need_id, investor_id, amount, status, created_by)"
                        + " VALUES (?,?,?,?,?) RETURNING id, created_at",
                r.needId(), r.investorId(), r.amount(), status, actor);
        UUID id = (UUID) created.get("id");
        for (RiskEngine.RuleResult res : outcome.results()) {
            db.update("INSERT INTO risk_decisions(commitment_id, rule_id, verdict, reason, params) VALUES (?,?,?,?,?::jsonb)",
                    id, res.ruleId(), res.decision().verdict().name(), res.decision().reason(),
                    json.writeValueAsString(res.params()));
        }
        List<DecisionView> views = outcome.results().stream()
                .map(x -> new DecisionView(x.ruleId(), x.decision().verdict().name(), x.decision().reason())).toList();
        Response body = new Response(id, r.needId(), r.investorId(), r.amount(), status, remaining, views,
                ((java.sql.Timestamp) created.get("created_at")).toInstant());

        // Only a real decision is audited. A replay (same Idempotency-Key) never reaches this line.
        metrics.commitmentDecided(status);
        audit.record("COMMITMENT_" + status, "commitment:" + id,
                Map.of("needId", r.needId().toString(), "investorId", r.investorId().toString(),
                        "amount", r.amount().toPlainString()));
        // Events in the same transaction (outbox). A flagged commitment is only "requested" until someone decides.
        var facts = new CommitmentEvents.Facts(id, r.needId(), r.investorId(), r.amount());
        events.requested(facts);
        if (outcome.verdict() == Verdict.PASS) {
            events.accepted(facts);
        } else if (outcome.verdict() == Verdict.BLOCK) {
            events.rejected(facts, outcome.results().stream().filter(x -> x.decision().verdict() == Verdict.BLOCK)
                    .map(x -> x.ruleId() + ": " + x.decision().reason()).collect(java.util.stream.Collectors.joining("; ")));
        }
        int http = switch (outcome.verdict()) {
            case PASS -> HttpStatus.CREATED.value();          // 201: accepted
            case FLAG -> HttpStatus.ACCEPTED.value();         // 202: recorded, waiting for a second person
            case BLOCK -> HttpStatus.UNPROCESSABLE_ENTITY.value(); // 422: recorded as rejected, with the reasons
        };
        return new Saved(http, json.writeValueAsString(body), false);
    }

    // ---- second-person review of FLAGGED commitments ----

    /** Four eyes: whoever recorded a commitment cannot be the one who approves or rejects it. */
    @Transactional
    Response review(UUID commitmentId, boolean approve, UUID reviewer) {
        var rows = db.queryForList("SELECT need_id, investor_id, amount, status, created_by FROM commitments WHERE id = ? FOR UPDATE",
                commitmentId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Commitment not found");
        }
        var row = rows.get(0);
        if (!"PENDING_REVIEW".equals(row.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Commitment is not pending review");
        }
        if (reviewer.equals(row.get("created_by"))) {
            throw new AccessDeniedException("The person who recorded a commitment cannot review it");
        }
        db.update("UPDATE commitments SET status = ?, reviewed_by = ?, reviewed_at = now() WHERE id = ?",
                approve ? "ACCEPTED" : "REJECTED", reviewer, commitmentId);
        if (!approve) {
            // give the reserved capacity back
            db.update("UPDATE funding_needs SET remaining_capacity = remaining_capacity + ?, version = version + 1 WHERE id = ?",
                    row.get("amount"), row.get("need_id"));
        }
        audit.record(approve ? "COMMITMENT_REVIEW_APPROVED" : "COMMITMENT_REVIEW_REJECTED", "commitment:" + commitmentId, Map.of());
        var facts = new CommitmentEvents.Facts(commitmentId, (UUID) row.get("need_id"), (UUID) row.get("investor_id"),
                (BigDecimal) row.get("amount"));
        if (approve) {
            events.accepted(facts);
        } else {
            events.rejected(facts, "Rejected by reviewer");
        }
        return get(commitmentId);
    }

    @Transactional(readOnly = true)
    Response get(UUID id) {
        List<Response> rows = db.query("SELECT c.id, c.need_id, c.investor_id, c.amount, c.status, c.created_at,"
                        + " n.remaining_capacity FROM commitments c JOIN funding_needs n ON n.id = c.need_id WHERE c.id = ?",
                (rs, n) -> new Response(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class),
                        rs.getObject("investor_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getBigDecimal("remaining_capacity"), List.of(), rs.getTimestamp("created_at").toInstant()), id);
        if (rows.isEmpty()) {
            throw new NotFoundException("Commitment not found");
        }
        Response r = rows.get(0);
        List<DecisionView> decisions = db.query(
                "SELECT rule_id, verdict, reason FROM risk_decisions WHERE commitment_id = ? ORDER BY rule_id",
                (rs, n) -> new DecisionView(rs.getString("rule_id"), rs.getString("verdict"), rs.getString("reason")), id);
        return new Response(r.id(), r.needId(), r.investorId(), r.amount(), r.status(), r.remainingCapacity(),
                decisions, r.createdAt());
    }

    // ---- helpers ----

    private record NeedRow(UUID issuerId, BigDecimal remaining) {}

    /**
     * Reserve capacity with one conditional UPDATE (check and change together). With the row already locked
     * and the capacity rule in front of it this cannot fail, but the database stays the final guard.
     */
    private BigDecimal takeCapacity(UUID needId, BigDecimal amount) {
        List<BigDecimal> left = db.queryForList(
                "UPDATE funding_needs SET remaining_capacity = remaining_capacity - ?, version = version + 1"
                        + " WHERE id = ? AND remaining_capacity >= ? RETURNING remaining_capacity",
                BigDecimal.class, amount, needId, amount);
        if (left.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Amount exceeds the remaining capacity of this funding need");
        }
        return left.get(0);
    }

    /** Ticket ranges of the investor's mandates for this need's sector and region. */
    private List<RiskContext.TicketRange> mandateRanges(CreateRequest r) {
        return db.query("SELECT m.min_ticket, m.max_ticket FROM mandates m JOIN funding_needs n"
                        + " ON n.sector = m.sector AND n.region = m.region WHERE n.id = ? AND m.investor_id = ?",
                (rs, n) -> new RiskContext.TicketRange(rs.getBigDecimal("min_ticket"), rs.getBigDecimal("max_ticket")),
                r.needId(), r.investorId());
    }

    /** History questions for the rules, asked only if a rule actually asks. */
    private final class Facts implements RiskFacts {
        private final CreateRequest r;
        private final NeedRow need;

        Facts(CreateRequest r, NeedRow need) {
            this.r = r;
            this.need = need;
        }

        @Override
        public BigDecimal exposureToIssuer() {
            return db.queryForObject("SELECT coalesce(sum(c.amount), 0) FROM commitments c JOIN funding_needs n ON n.id = c.need_id"
                    + " WHERE n.issuer_id = ? AND c.investor_id = ? AND c.status IN ('ACCEPTED','PENDING_REVIEW')",
                    BigDecimal.class, need.issuerId(), r.investorId());
        }

        @Override
        public int investorCommitmentsWithin(Duration window) {
            return count("SELECT count(*) FROM commitments WHERE investor_id = ?"
                    + " AND created_at > clock_timestamp() - make_interval(mins => ?)", r.investorId(), (int) window.toMinutes());
        }

        @Override
        public int identicalCommitmentsWithin(Duration window) {
            return count("SELECT count(*) FROM commitments WHERE investor_id = ? AND need_id = ? AND amount = ?"
                    + " AND created_at > clock_timestamp() - make_interval(mins => ?)",
                    r.investorId(), r.needId(), r.amount(), (int) window.toMinutes());
        }
    }

    private int count(String sql, Object... args) {
        Integer n = db.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required on every JVM
        }
    }
}
