package com.issuermatch.commitment;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires old idempotency keys so the table does not grow forever. After the limit (24h by default) a repeated key
 * is treated as a new request: clients must not retry a write older than that, which is the usual contract.
 */
@Component
class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final JdbcTemplate db;
    private final Duration ttl;

    IdempotencyCleanup(JdbcTemplate db, @Value("${app.idempotency.ttl:24h}") Duration ttl) {
        this.db = db;
        this.ttl = ttl;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval:1h}", initialDelayString = "${app.idempotency.cleanup-interval:1h}")
    void run() {
        try {
            int n = removeOlderThan(ttl);
            if (n > 0) log.info("Removed {} expired idempotency key(s)", n);
        } catch (Exception e) {
            log.warn("Idempotency cleanup failed, will retry: {}", e.toString());
        }
    }

    int removeOlderThan(Duration age) {
        return db.update("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(secs => ?)", age.toSeconds());
    }
}
