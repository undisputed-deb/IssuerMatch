package com.issuermatch.messaging;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumer-side input validation: a Kafka message is untrusted input, exactly like an HTTP body. */
@Component
public class EventParser {

    private final JsonMapper json;

    EventParser(JsonMapper json) {
        this.json = json;
    }

    /** @throws InvalidEventException when the message is unreadable or incomplete (goes to the dead-letter topic) */
    public EventEnvelope parse(String message) {
        try {
            EventEnvelope e = json.readValue(message, EventEnvelope.class);
            if (e.eventId() == null || e.payload() == null) {
                throw new InvalidEventException("Event is missing eventId or payload");
            }
            return e;
        } catch (InvalidEventException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidEventException("Unreadable event", e);
        }
    }
}
