package com.issuermatch.messaging;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OutboxService {

    private final JdbcTemplate db;
    private final JsonMapper json;

    OutboxService(JdbcTemplate db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    /**
     * Queue an event. MANDATORY: must be called inside the caller's business transaction,
     * otherwise the whole point (data + event commit together) is lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope publish(String topic, String key, String type, Map<String, Object> payload) {
        EventEnvelope event = EventEnvelope.of(type, payload);
        db.update("INSERT INTO outbox(topic,event_key,payload) VALUES (?,?,?)", topic, key, json.writeValueAsString(event));
        return event;
    }
}
