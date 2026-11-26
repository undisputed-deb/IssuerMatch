package com.issuermatch.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableScheduling
class KafkaConfig {

    // One partition keeps ordering simple for a single-node dev setup. Raise partitions to scale consumers.
    @Bean NewTopic fundingNeedSubmitted() { return topic(Topics.FUNDING_NEED_SUBMITTED); }
    @Bean NewTopic fundingNeedSubmittedDlt() { return topic(Topics.FUNDING_NEED_SUBMITTED + ".DLT"); }
    @Bean NewTopic matchRequested() { return topic(Topics.MATCH_REQUESTED); }
    @Bean NewTopic matchRequestedDlt() { return topic(Topics.MATCH_REQUESTED + ".DLT"); }
    @Bean NewTopic matchCompleted() { return topic(Topics.MATCH_COMPLETED); }
    @Bean NewTopic auditEvents() { return topic(Topics.AUDIT_EVENTS); }
    @Bean NewTopic commitmentRequested() { return topic(Topics.COMMITMENT_REQUESTED); }
    @Bean NewTopic commitmentRequestedDlt() { return topic(Topics.COMMITMENT_REQUESTED + ".DLT"); }
    @Bean NewTopic commitmentAccepted() { return topic(Topics.COMMITMENT_ACCEPTED); }
    @Bean NewTopic commitmentAcceptedDlt() { return topic(Topics.COMMITMENT_ACCEPTED + ".DLT"); }
    @Bean NewTopic commitmentRejected() { return topic(Topics.COMMITMENT_REJECTED); }
    @Bean NewTopic commitmentRejectedDlt() { return topic(Topics.COMMITMENT_REJECTED + ".DLT"); }
    @Bean NewTopic commitmentDeclined() { return topic(Topics.COMMITMENT_DECLINED); }
    @Bean NewTopic commitmentDeclinedDlt() { return topic(Topics.COMMITMENT_DECLINED + ".DLT"); }

    /** Pinned explicitly: Spring Kafka 4 changed its default suffix to "-dlt". Partition -1 = producer picks. */
    private static TopicPartition deadLetterTopic(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception e) {
        return new TopicPartition(record.topic() + ".DLT", -1);
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    /**
     * Failed message: retry 3 times with 0.5s, 1s, 2s waits, then publish it to "<topic>.DLT" with the
     * exception details in headers. Invalid events skip the retries (retrying a bad message is pointless).
     */
    @Bean
    CommonErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxElapsedTime(3_500);
        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template, KafkaConfig::deadLetterTopic), backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
