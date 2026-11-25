package com.issuermatch.reconciliation;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final Duration KEEP_REPORTS = Duration.ofDays(7);

    private final ReconciliationService service;

    ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.reconciliation.interval:5m}", initialDelayString = "${app.reconciliation.interval:5m}")
    void run() {
        try {
            service.run(null);
            service.removeOlderThan(KEEP_REPORTS);
        } catch (Exception e) {
            log.error("Reconciliation job failed, will try again next interval", e);
        }
    }
}
