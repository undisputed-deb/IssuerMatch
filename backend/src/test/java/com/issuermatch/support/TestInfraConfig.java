package com.issuermatch.support;

import com.issuermatch.matching.agent.FakeLlmClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real Postgres and Kafka in Docker for tests. Never touches Supabase. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfraConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    /**
     * Every MockMvc request is an ADMIN unless the test says otherwise (.with(Auth.issuer(..)), Auth.anonymous(), ...).
     * Keeps the feature tests about the feature. Authorization has its own tests.
     */
    @Bean
    MockMvcBuilderCustomizer adminByDefault() {
        return builder -> builder.defaultRequest(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").with(Auth.admin()));
    }

    /** Replaces Gemini in every Spring test: no test ever calls the real API. */
    @Bean
    @Primary
    FakeLlmClient fakeLlmClient() {
        return FakeLlmClient.lenient();
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:3.8.0");
    }
}
