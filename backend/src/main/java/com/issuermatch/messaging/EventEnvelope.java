package com.issuermatch.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Every Kafka message has this shape. eventId is what consumers use to detect duplicates. */
public record EventEnvelope(UUID eventId, String type, Instant occurredAt, String correlationId,
                            Map<String, Object> payload) {

    public static EventEnvelope of(String type, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, Instant.now(), UUID.randomUUID().toString(), payload);
    }

    /** Required UUID field. Throws InvalidEventException (never retried) when missing or malformed. */
    public UUID uuid(String field) {
        Object v = payload == null ? null : payload.get(field);
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Event " + eventId + " has invalid payload field '" + field + "'");
        }
    }
}
