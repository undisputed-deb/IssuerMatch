package com.issuermatch.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

@IntegrationTest
class OutboxPublisherTest {

    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired KafkaContainer kafkaContainer;

    @Test
    void aBacklogLargerThanOneBatchIsPublishedCompletelyAndInOrder() {
        String key = "order-" + UUID.randomUUID();
        // 1200 rows = more than two batches, same key so they share a partition and must arrive in order
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 1200; i++) {
                db.update("INSERT INTO outbox(topic,event_key,payload,created_at) VALUES ('match.completed',?,?, clock_timestamp())",
                        key, String.valueOf(i));
            }
        });

        await().atMost(Duration.ofSeconds(30)).until(() -> db.queryForObject(
                "SELECT count(*) FROM outbox WHERE event_key = ? AND published_at IS NULL", Integer.class, key) == 0);

        List<String> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("match.completed"));
            long end = System.currentTimeMillis() + 30_000;
            while (received.size() < 1200 && System.currentTimeMillis() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) received.add(r.value());
                });
            }
        }
        assertThat(received).containsExactlyElementsOf(IntStream.range(0, 1200).mapToObj(String::valueOf).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ifAnySendFailsNothingIsMarkedPublished() {
        KafkaTemplate<String, String> flaky = mock(KafkaTemplate.class);
        when(flaky.send(anyString(), anyString(), anyString())).thenAnswer(call -> "bad".equals(call.getArgument(2))
                ? CompletableFuture.failedFuture(new IllegalStateException("broker said no"))
                : CompletableFuture.completedFuture((SendResult<String, String>) null));
        String key = "flaky-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> { // uncommitted: the real scheduled publisher cannot see these rows
            db.update("INSERT INTO outbox(topic,event_key,payload) VALUES ('match.completed',?,'good')", key);
            db.update("INSERT INTO outbox(topic,event_key,payload) VALUES ('match.completed',?,'bad')", key);
            new OutboxPublisher(db, flaky, tx).publishPending();
            assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE event_key = ? AND published_at IS NULL",
                    Integer.class, key)).isEqualTo(2);
            status.setRollbackOnly();
        });
    }
}
