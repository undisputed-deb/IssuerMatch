package com.issuermatch.deadletter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class DeadLetterListenerConfig {

    /**
     * The dead-letter consumer must never dead-letter its own failures (that would make "x.DLT.DLT" and lose the
     * message). If storing fails, for example because the database is down, it retries every 5 seconds until it works.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterListenerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(5_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
