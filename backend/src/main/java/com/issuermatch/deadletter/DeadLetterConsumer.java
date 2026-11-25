package com.issuermatch.deadletter;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/** Copies every dead-lettered message into the database, with the original payload and why it failed. */
@Component
class DeadLetterConsumer {

    private static final int MAX_REASON = 2000;

    private final JdbcTemplate db;

    DeadLetterConsumer(JdbcTemplate db) {
        this.db = db;
    }

    @KafkaListener(topicPattern = ".*\\.DLT", groupId = "issuermatch-dead-letters",
            containerFactory = "deadLetterListenerFactory")
    public void store(ConsumerRecord<String, String> record) {
        String source = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (source == null) {
            source = record.topic().substring(0, record.topic().length() - ".DLT".length());
        }
        db.update("INSERT INTO dead_letters(source_topic, message_key, payload, reason, dlt_topic, dlt_partition, dlt_offset)"
                        + " VALUES (?,?,?,?,?,?,?) ON CONFLICT ON CONSTRAINT uq_dead_letter_record DO NOTHING",
                source, record.key() == null ? "" : record.key(), record.value() == null ? "" : record.value(),
                reason(record), record.topic(), record.partition(), record.offset());
    }

    /** The root cause (last "Caused by:" line) is the useful part: the outer message only says a listener failed. */
    private static String reason(ConsumerRecord<String, String> record) {
        String reason = null;
        String trace = header(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        if (trace != null) {
            for (String line : trace.split("\\R")) {
                if (line.startsWith("Caused by: ")) reason = line.substring("Caused by: ".length());
            }
        }
        if (reason == null) reason = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (reason == null) reason = "unknown";
        return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
