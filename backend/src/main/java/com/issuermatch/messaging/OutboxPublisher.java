package com.issuermatch.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed outbox rows to Kafka. At-least-once: a crash after send but before the update
 * re-sends the row, which is why every consumer is idempotent.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 100;

    private final JdbcTemplate db;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    OutboxPublisher(JdbcTemplate db, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.db = db;
        this.kafka = kafka;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
    void publishPending() {
        try {
            while (Boolean.TRUE.equals(tx.execute(status -> publishBatch()))) {
                // keep draining while full batches come back
            }
        } catch (Exception e) {
            log.warn("Outbox publish failed, will retry next tick: {}", e.toString());
        }
    }

    /** @return true if the batch was full (there may be more) */
    private boolean publishBatch() {
        // SKIP LOCKED: two app instances never grab the same rows.
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, topic, event_key, payload FROM outbox WHERE published_at IS NULL"
                        + " ORDER BY created_at LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> row : rows) {
            try {
                kafka.send((String) row.get("topic"), (String) row.get("event_key"), (String) row.get("payload"))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("send failed for outbox row " + row.get("id"), e);
            }
            db.update("UPDATE outbox SET published_at = now() WHERE id = ?", (UUID) row.get("id"));
        }
        return rows.size() == BATCH;
    }
}
