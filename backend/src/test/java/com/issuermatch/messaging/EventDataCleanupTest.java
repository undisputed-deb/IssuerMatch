package com.issuermatch.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class EventDataCleanupTest {

    @Autowired EventDataCleanup cleanup;
    @Autowired JdbcTemplate db;

    @Test
    void removesOldFinishedWorkAndNeverUnpublishedOrRecentRows() {
        String key = "purge-" + UUID.randomUUID();
        db.update("INSERT INTO outbox(topic,event_key,payload,published_at) VALUES ('t',?,'old', now() - interval '8 days')", key);
        db.update("INSERT INTO outbox(topic,event_key,payload,published_at) VALUES ('t',?,'recent', now() - interval '6 days')", key);
        db.update("INSERT INTO outbox(topic,event_key,payload,created_at) VALUES ('t',?,'stuck', now() - interval '30 days')", key);
        UUID oldEvent = UUID.randomUUID();
        UUID newEvent = UUID.randomUUID();
        db.update("INSERT INTO processed_events(event_id,consumer,processed_at) VALUES (?,'c', now() - interval '31 days')", oldEvent);
        db.update("INSERT INTO processed_events(event_id,consumer,processed_at) VALUES (?,'c', now() - interval '29 days')", newEvent);

        cleanup.purge();

        assertThat(db.queryForList("SELECT payload FROM outbox WHERE event_key = ? ORDER BY payload", String.class, key))
                .containsExactly("recent", "stuck");
        assertThat(db.queryForList("SELECT event_id FROM processed_events WHERE event_id IN (?,?)", UUID.class, oldEvent, newEvent))
                .containsExactly(newEvent);
    }
}
