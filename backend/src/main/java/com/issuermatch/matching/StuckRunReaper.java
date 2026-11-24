package com.issuermatch.matching;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safety net: a run stuck in RUNNING (crash, restart) becomes FAILED instead of hanging forever. */
@Component
class StuckRunReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckRunReaper.class);

    private final MatchRunService runs;
    private final Duration stuckAfter;

    // Must be comfortably longer than the agent's own time limit (app.agent.timeout, 60s by default).
    StuckRunReaper(MatchRunService runs, @Value("${app.agent.stuck-after:5m}") Duration stuckAfter) {
        this.runs = runs;
        this.stuckAfter = stuckAfter;
    }

    @Scheduled(fixedDelayString = "${app.agent.reaper-interval:60s}")
    void reap() {
        try {
            int n = runs.reapStuck(stuckAfter);
            if (n > 0) log.warn("Failed {} match run(s) stuck in RUNNING for over {}", n, stuckAfter);
        } catch (Exception e) {
            log.warn("Stuck-run reaper failed, will retry: {}", e.toString());
        }
    }
}
