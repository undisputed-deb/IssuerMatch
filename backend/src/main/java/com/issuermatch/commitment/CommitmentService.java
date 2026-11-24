package com.issuermatch.commitment;

import com.issuermatch.commitment.CommitmentDtos.CreateRequest;
import com.issuermatch.commitment.CommitmentDtos.Response;
import com.issuermatch.commitment.IdempotencyService.Saved;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class CommitmentService {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final JdbcTemplate db;
    private final IdempotencyService idempotency;
    private final JsonMapper json;

    CommitmentService(JdbcTemplate db, IdempotencyService idempotency, JsonMapper json) {
        this.db = db;
        this.idempotency = idempotency;
        this.json = json;
    }

    /** Idempotent: the whole thing (key + commitment) is one transaction. */
    @Transactional
    Saved create(UUID actor, String idempotencyKey, CreateRequest request) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header is required (1-128 characters: letters, digits, . _ : -)");
        }
        return idempotency.execute(actor, idempotencyKey, sha256(request.canonical()), () -> doCreate(actor, request));
    }

    private Saved doCreate(UUID actor, CreateRequest r) {
        if (count("SELECT count(*) FROM funding_needs WHERE id = ?", r.needId()) == 0) {
            throw new NotFoundException("Funding need not found");
        }
        if (count("SELECT count(*) FROM investors WHERE id = ?", r.investorId()) == 0) {
            throw new NotFoundException("Investor not found");
        }
        // Only an investor an analyst approved for this need may commit to it.
        if (count("SELECT count(*) FROM match_results r JOIN match_runs m ON m.id = r.run_id"
                + " WHERE m.need_id = ? AND r.investor_id = ? AND m.status = 'APPROVED'", r.needId(), r.investorId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Investor is not an approved match for this funding need");
        }
        Response created = db.queryForObject(
                "INSERT INTO commitments(need_id, investor_id, amount, status, created_by) VALUES (?,?,?,'ACCEPTED',?)"
                        + " RETURNING id, need_id, investor_id, amount, status, created_at",
                (rs, n) -> new Response(rs.getObject("id", UUID.class), rs.getObject("need_id", UUID.class),
                        rs.getObject("investor_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                r.needId(), r.investorId(), r.amount(), actor);
        return new Saved(HttpStatus.CREATED.value(), json.writeValueAsString(created), false);
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
