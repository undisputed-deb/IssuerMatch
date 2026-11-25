package com.issuermatch.commitment.risk;

import com.issuermatch.audit.AuditService;
import com.issuermatch.common.ApiException;
import com.issuermatch.common.NotFoundException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thresholds are data. A rule's effective settings = its built-in defaults overlaid with what an admin stored,
 * so a change takes effect on the next commitment, with no redeploy. Only overrides are stored, so a rule whose
 * built-in default changes in a later release is not frozen at the old value.
 */
@Service
public class RiskRuleConfigService {

    public record Effective(RiskRule rule, boolean enabled, Map<String, BigDecimal> params,
                            UUID updatedBy, Instant updatedAt) {}

    private record Stored(boolean enabled, Map<String, BigDecimal> params, UUID updatedBy, Instant updatedAt) {}

    private static final TypeReference<Map<String, BigDecimal>> PARAMS = new TypeReference<>() {};

    private final List<RiskRule> rules;
    private final JdbcTemplate db;
    private final JsonMapper json;
    private final AuditService audit;

    RiskRuleConfigService(List<RiskRule> rules, JdbcTemplate db, JsonMapper json, AuditService audit) {
        this.rules = rules.stream().sorted(Comparator.comparing(RiskRule::id)).toList();
        this.db = db;
        this.json = json;
        this.audit = audit;
    }

    /** Every known rule with its effective settings. One query. */
    public List<Effective> all() {
        Map<String, Stored> stored = new HashMap<>();
        db.query("SELECT rule_id, enabled, params::text AS params, updated_by, updated_at FROM risk_rule_config", rs -> {
            Timestamp at = rs.getTimestamp("updated_at");
            stored.put(rs.getString("rule_id"), new Stored(rs.getBoolean("enabled"),
                    json.readValue(rs.getString("params"), PARAMS), rs.getObject("updated_by", UUID.class),
                    at == null ? null : at.toInstant()));
        });
        return rules.stream().map(r -> effective(r, stored.get(r.id()))).toList();
    }

    public Effective get(String ruleId) {
        return all().stream().filter(e -> e.rule().id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Risk rule not found"));
    }

    @Transactional
    public Effective update(String ruleId, boolean enabled, Map<String, BigDecimal> overrides, UUID actor) {
        RiskRule rule = rules.stream().filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Risk rule not found"));
        Map<String, BigDecimal> merged = new LinkedHashMap<>(rule.defaults());
        for (Map.Entry<String, BigDecimal> e : overrides.entrySet()) {
            if (!rule.defaults().containsKey(e.getKey())) {
                throw bad("Unknown parameter '" + e.getKey() + "'. Allowed: " + rule.defaults().keySet());
            }
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw bad("Parameter '" + e.getKey() + "' must be a number of 0 or more");
            }
            merged.put(e.getKey(), e.getValue());
        }
        try {
            rule.validate(merged);
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        db.update("INSERT INTO risk_rule_config(rule_id, enabled, params, updated_by, updated_at) VALUES (?,?,?::jsonb,?,now())"
                        + " ON CONFLICT (rule_id) DO UPDATE SET enabled = EXCLUDED.enabled, params = EXCLUDED.params,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                ruleId, enabled, json.writeValueAsString(overrides), actor);
        // Changing risk limits is exactly what an auditor asks about. Numbers only.
        audit.record("RISK_RULE_UPDATED", "risk-rule:" + ruleId, Map.of("enabled", enabled, "params", overrides));
        return get(ruleId);
    }

    private static Effective effective(RiskRule rule, Stored stored) {
        Map<String, BigDecimal> params = new LinkedHashMap<>(rule.defaults());
        if (stored == null) {
            return new Effective(rule, true, params, null, null);
        }
        params.putAll(stored.params());
        return new Effective(rule, stored.enabled(), params, stored.updatedBy(), stored.updatedAt());
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
