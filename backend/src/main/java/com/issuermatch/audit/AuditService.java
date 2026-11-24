package com.issuermatch.audit;

import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.Topics;
import com.issuermatch.security.CurrentUser;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who did what, when, from where. Written in the SAME transaction as the action it describes (so there is never an
 * action without a record, or a record of something that rolled back), and mirrored to the audit.events topic.
 * Details must be ids and numbers only: never free text, tokens or other personal data.
 */
@Service
public class AuditService {

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final OutboxService outbox;

    AuditService(JdbcTemplate db, JsonMapper json, OutboxService outbox) {
        this.db = db;
        this.json = json;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String resource, Map<String, Object> details) {
        String actor = actor();
        String ip = ip();
        // clock_timestamp(), not the column default now(): now() is the transaction's START time, the audit trail wants the moment it happened
        db.update("INSERT INTO audit_log(actor, action, resource, ip, details, at) VALUES (?,?,?,?,?::jsonb, clock_timestamp())",
                actor, action, resource, ip, json.writeValueAsString(details));
        Map<String, Object> payload = new HashMap<>(Map.of("actor", actor, "action", action, "resource", resource,
                "ip", ip == null ? "unknown" : ip));
        payload.put("details", details);
        outbox.publish(Topics.AUDIT_EVENTS, actor, "AuditEvent", payload);
    }

    private static String actor() {
        try {
            return CurrentUser.get().id().toString();
        } catch (RuntimeException e) {
            return "system"; // background work (consumers, schedulers) has no caller
        }
    }

    /**
     * The socket address. X-Forwarded-For is NOT used: any client can send it, so it would let a caller
     * write a false address into the audit log. Behind a trusted proxy, configure server.forward-headers-strategy instead.
     */
    private static String ip() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest().getRemoteAddr() : null;
    }
}
