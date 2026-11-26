package com.issuermatch.common;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * One id that follows a request through HTTP, the outbox, Kafka and the consumers, and appears on every log line.
 * Values from outside are accepted only if they are short and harmless, so nobody can forge log lines with them.
 */
public final class CorrelationId {

    public static final String KEY = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {}

    /** The candidate if it is safe, otherwise a new id. */
    public static String sanitise(String candidate) {
        return candidate != null && SAFE.matcher(candidate).matches() ? candidate : UUID.randomUUID().toString();
    }

    /** The id of the current request or message, or a new one when there is none. */
    public static String current() {
        String id = MDC.get(KEY);
        return id == null ? UUID.randomUUID().toString() : id;
    }
}
