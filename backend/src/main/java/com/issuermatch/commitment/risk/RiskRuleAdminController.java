package com.issuermatch.commitment.risk;

import com.issuermatch.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/** Admins tune risk thresholds at runtime. Takes effect on the next commitment. */
@RestController
@RequestMapping("/admin/risk-rules")
@PreAuthorize("hasRole('ADMIN')")
class RiskRuleAdminController {

    record View(String id, String description, boolean enabled, Map<String, BigDecimal> params,
                Map<String, BigDecimal> defaults, UUID updatedBy, Instant updatedAt) {
        static View from(RiskRuleConfigService.Effective e) {
            return new View(e.rule().id(), e.rule().description(), e.enabled(), e.params(), e.rule().defaults(),
                    e.updatedBy(), e.updatedAt());
        }
    }

    /** params holds only the values to override. Anything left out keeps its built-in default. */
    record UpdateRequest(@NotNull Boolean enabled, @NotNull Map<String, BigDecimal> params) {}

    private final RiskRuleConfigService configs;

    RiskRuleAdminController(RiskRuleConfigService configs) {
        this.configs = configs;
    }

    @GetMapping
    List<View> list() {
        return configs.all().stream().map(View::from).toList();
    }

    @GetMapping("/{id}")
    View get(@PathVariable String id) {
        return View.from(configs.get(id));
    }

    @PutMapping("/{id}")
    View update(@PathVariable String id, @Valid @RequestBody UpdateRequest request) {
        return View.from(configs.update(id, request.enabled(), request.params(), CurrentUser.get().id()));
    }
}
