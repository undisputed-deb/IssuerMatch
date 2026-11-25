package com.issuermatch.deadletter;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeadLetterService {

    record View(UUID id, String sourceTopic, String key, String reason, String payload, Instant receivedAt,
                Instant replayedAt, UUID replayedBy) {}

    enum Status {
        PENDING, REPLAYED
    }

    private static final String COLUMNS =
            "id, source_topic, message_key, reason, payload, received_at, replayed_at, replayed_by";

    private final JdbcTemplate db;
    private final OutboxService outbox;
    private final AuditService audit;

    DeadLetterService(JdbcTemplate db, OutboxService outbox, AuditService audit) {
        this.db = db;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    Page<View> list(String topic, Status status, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (topic != null) {
            where.append(" AND source_topic = ?");
            args.add(topic);
        }
        if (status != null) { // fixed text chosen from an enum, never user input
            where.append(status == Status.PENDING ? " AND replayed_at IS NULL" : " AND replayed_at IS NOT NULL");
        }
        Long total = db.queryForObject("SELECT count(*) FROM dead_letters" + where, Long.class, args.toArray());
        List<View> rows = db.query("SELECT " + COLUMNS + " FROM dead_letters" + where + " ORDER BY received_at DESC LIMIT "
                + pageable.getPageSize() + " OFFSET " + pageable.getOffset(), (rs, n) -> view(rs), args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    View get(UUID id) {
        return db.query("SELECT " + COLUMNS + " FROM dead_letters WHERE id = ?", (rs, n) -> view(rs), id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Dead letter not found"));
    }

    /**
     * Send the original message back to its original topic, once. It goes through the outbox in the same transaction
     * as the "replayed" mark, so the two cannot disagree. The payload (and so its eventId) is unchanged: consumers
     * that already handled it will recognise it and skip it.
     */
    @Transactional
    View replay(UUID id, UUID actor) {
        int claimed = db.update("UPDATE dead_letters SET replayed_at = now(), replayed_by = ? WHERE id = ? AND replayed_at IS NULL",
                actor, id);
        View letter = get(id); // 404 for an unknown id
        if (claimed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Dead letter was already replayed");
        }
        // The topic name came from a Kafka header, so check it: replay only into topics this application consumes.
        if (!Topics.CONSUMED.contains(letter.sourceTopic())) {
            throw new ApiException(HttpStatus.CONFLICT, "This application does not consume that topic");
        }
        outbox.republish(letter.sourceTopic(), letter.key(), letter.payload());
        audit.record("DEAD_LETTER_REPLAYED", "dead-letter:" + id, Map.of("topic", letter.sourceTopic()));
        return letter;
    }

    private static View view(ResultSet rs) throws SQLException {
        return new View(rs.getObject("id", UUID.class), rs.getString("source_topic"), rs.getString("message_key"),
                rs.getString("reason"), rs.getString("payload"), instant(rs.getTimestamp("received_at")),
                instant(rs.getTimestamp("replayed_at")), rs.getObject("replayed_by", UUID.class));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
