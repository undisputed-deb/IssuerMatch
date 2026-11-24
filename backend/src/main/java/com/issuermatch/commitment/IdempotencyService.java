package com.issuermatch.commitment;

import com.issuermatch.common.ApiException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a write safe to retry. The key row is inserted in the SAME transaction as the work:
 *  - first caller: inserts the key, does the work, saves the response, commits;
 *  - any concurrent caller with the same key blocks on the unique index until the first one commits,
 *    then finds the saved response and replays it. It never runs the work a second time;
 *  - if the work fails, everything (including the key) rolls back, so the client can retry with the same key.
 */
@Service
class IdempotencyService {

    record Saved(int status, String body, boolean replayed) {}

    private final JdbcTemplate db;

    IdempotencyService(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Saved execute(String key, String requestHash, Supplier<Saved> work) {
        int inserted = db.update("INSERT INTO idempotency_keys(idem_key, request_hash) VALUES (?, ?) ON CONFLICT DO NOTHING",
                key, requestHash);
        if (inserted == 1) {
            Saved saved = work.get();
            db.update("UPDATE idempotency_keys SET status_code = ?, response_body = ? WHERE idem_key = ?",
                    saved.status(), saved.body(), key);
            return saved;
        }
        // Someone else owns this key and has committed (our insert waited for them).
        List<Stored> rows = db.query("SELECT request_hash, status_code, response_body FROM idempotency_keys WHERE idem_key = ?",
                (rs, n) -> new Stored(rs.getString("request_hash"), (Integer) rs.getObject("status_code"),
                        rs.getString("response_body")), key);
        if (rows.isEmpty()) {
            // The owner rolled back between our insert and select. Ask the client to simply retry.
            throw new ApiException(HttpStatus.CONFLICT, "Request with this Idempotency-Key was in flight. Retry.");
        }
        Stored stored = rows.get(0);
        if (!stored.hash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key was already used with a different request body");
        }
        if (stored.status() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Request with this Idempotency-Key is still being processed. Retry.");
        }
        return new Saved(stored.status(), stored.body(), true);
    }

    private record Stored(String hash, Integer status, String body) {}
}
