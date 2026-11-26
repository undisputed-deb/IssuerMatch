package com.issuermatch.messaging;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final int BATCH = 500;
    private static final int SEND_TIMEOUT_SECONDS = 30;

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

    /**
     * Sends a whole batch without waiting between messages (the producer is idempotent, so order per partition is
     * kept), then waits for every acknowledgement, then marks the batch published with one statement. If any send
     * fails nothing is marked and the batch is sent again later; consumers are idempotent, so repeats are harmless.
     * Sending one at a time was the bottleneck the load test found (about 180 events/s).
     *
     * @return true if the batch was full (there may be more)
     */
    private boolean publishBatch() {
        // SKIP LOCKED: two app instances never grab the same rows.
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT id, topic, event_key, payload FROM outbox WHERE published_at IS NULL"
                        + " ORDER BY created_at LIMIT " + BATCH + " FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) {
            return false;
        }
        CompletableFuture<?>[] sends = rows.stream()
                .map(row -> kafka.send((String) row.get("topic"), (String) row.get("event_key"), (String) row.get("payload")))
                .toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(sends).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish a batch of " + rows.size() + " outbox rows", e);
        }
        Object[] ids = rows.stream().map(row -> row.get("id")).toArray();
        db.update(connection -> {
            PreparedStatement mark = connection.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ANY(?)");
            mark.setArray(1, connection.createArrayOf("uuid", ids));
            return mark;
        });
        return rows.size() == BATCH;
    }
}
