package com.issuermatch.commitment;

import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What happened to a commitment, queued in the outbox inside the caller's transaction, so an event exists
 * exactly when the change did. Keyed by need id, so one need's events keep their order within a topic.
 */
@Component
class CommitmentEvents {

    private final OutboxService outbox;

    CommitmentEvents(OutboxService outbox) {
        this.outbox = outbox;
    }

    record Facts(UUID commitmentId, UUID needId, UUID investorId, BigDecimal amount) {}

    void requested(Facts f) {
        publish(Topics.COMMITMENT_REQUESTED, "CommitmentRequested", f, null);
    }

    void accepted(Facts f) {
        publish(Topics.COMMITMENT_ACCEPTED, "CommitmentAccepted", f, null);
    }

    void rejected(Facts f, String reason) {
        publish(Topics.COMMITMENT_REJECTED, "CommitmentRejected", f, reason);
    }

    void declined(Facts f) {
        publish(Topics.COMMITMENT_DECLINED, "CommitmentDeclined", f, "Declined by the issuer");
    }

    private void publish(String topic, String type, Facts f, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("commitmentId", f.commitmentId().toString());
        payload.put("needId", f.needId().toString());
        payload.put("investorId", f.investorId().toString());
        payload.put("amount", f.amount().toPlainString()); // string: money never goes through floating point
        if (reason != null) {
            payload.put("reason", reason);
        }
        outbox.publish(topic, f.needId().toString(), type, payload);
    }
}
