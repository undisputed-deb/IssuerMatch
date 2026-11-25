package com.issuermatch.reconciliation;

import com.issuermatch.audit.AuditService;
import com.issuermatch.reconciliation.ReconciliationService.Report;
import com.issuermatch.security.CurrentUser;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
class ReconciliationController {

    private final ReconciliationService service;
    private final AuditService audit;
    private final TransactionTemplate tx;

    ReconciliationController(ReconciliationService service, AuditService audit, TransactionTemplate tx) {
        this.service = service;
        this.audit = audit;
        this.tx = tx;
    }

    @PostMapping("/run")
    Report run() {
        Report report = service.run(CurrentUser.get().id());
        tx.executeWithoutResult(s -> audit.record("RECONCILIATION_RUN", "reconciliation:" + report.id(),
                Map.of("mismatchCount", report.mismatchCount())));
        return report;
    }

    @GetMapping("/latest")
    Report latest() {
        return service.latest();
    }
}
