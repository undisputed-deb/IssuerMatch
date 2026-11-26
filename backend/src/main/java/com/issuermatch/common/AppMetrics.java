package com.issuermatch.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The business numbers worth watching. Read them at /actuator/metrics/{name} (admin only).
 * Kafka consumer lag is already there from the Kafka client: kafka.consumer.fetch.manager.records.lag.max.
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;

    AppMetrics(MeterRegistry registry, JdbcTemplate db) {
        this.registry = registry;
        // Growing backlog means events are not leaving the database: the first thing the load test caught.
        Gauge.builder("issuermatch.outbox.backlog", db,
                        d -> d.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class))
                .description("Events waiting to be published").register(registry);
    }

    public void matchFinished(String status, Duration latency, int tokensIn, int tokensOut) {
        registry.timer("issuermatch.match.latency", "status", status).record(latency);
        registry.counter("issuermatch.llm.tokens", "direction", "in").increment(tokensIn);
        registry.counter("issuermatch.llm.tokens", "direction", "out").increment(tokensOut);
    }

    public void commitmentDecided(String status) {
        registry.counter("issuermatch.commitments", "status", status).increment();
    }
}
