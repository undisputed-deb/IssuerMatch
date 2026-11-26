package com.issuermatch.messaging;

import com.issuermatch.common.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Puts the event's correlation id on the consumer thread, so its log lines join up with the original request. */
@Component
class CorrelationIdInterceptor implements RecordInterceptor<Object, Object> {

    private final JsonMapper json;

    CorrelationIdInterceptor(JsonMapper json) {
        this.json = json;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        String id = null;
        try {
            id = json.readTree(String.valueOf(record.value())).path("correlationId").asString(null);
        } catch (RuntimeException e) {
            // unreadable message: the listener will reject it. It still gets an id of its own.
        }
        MDC.put(CorrelationId.KEY, CorrelationId.sanitise(id));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.KEY);
    }
}
