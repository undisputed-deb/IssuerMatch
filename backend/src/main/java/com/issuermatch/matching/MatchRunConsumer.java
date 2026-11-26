package com.issuermatch.matching;

import com.issuermatch.matching.agent.AgentFailure;
import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.messaging.EventParser;
import com.issuermatch.messaging.Topics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Step 2 of matching: run the agent for a match run. Deliberately NOT one big transaction: the model call can
 * take a minute and must not hold a database connection. Idempotency comes from the atomic claim
 * (REQUESTED -> RUNNING), so a redelivered event does nothing.
 * Agent problems never throw out of here: they become a FAILED run (safe message) and a match.completed event.
 */
@Component
class MatchRunConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchRunConsumer.class);

    private final EventParser parser;
    private final MatchRunService runs;
    private final MatchExecutor executor;

    MatchRunConsumer(EventParser parser, MatchRunService runs, MatchExecutor executor) {
        this.parser = parser;
        this.runs = runs;
        this.executor = executor;
    }

    @KafkaListener(topics = Topics.MATCH_REQUESTED)
    public void onMatchRequested(String message) {
        EventEnvelope event = parser.parse(message);
        UUID runId = event.uuid("runId");
        UUID needId = event.uuid("needId");

        if (!runs.claim(runId, needId)) {
            log.info("Match run {} already claimed (or unknown). Event {} ignored", runId, event.eventId());
            return;
        }
        try {
            MatchExecutor.Outcome outcome = executor.execute(needId);
            runs.complete(runId, needId, outcome.result(), outcome.mode().name(), outcome.degradedReason());
        } catch (AgentFailure e) {
            log.warn("Match run {} failed: {}", runId, e.getMessage());
            runs.fail(runId, needId, e.getMessage());
        } catch (Exception e) {
            log.error("Match run {} crashed", runId, e);
            runs.fail(runId, needId, "Unexpected error");
        }
    }
}
