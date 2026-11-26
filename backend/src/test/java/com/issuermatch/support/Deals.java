package com.issuermatch.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Committed fixtures for deal tests: a company owned by an issuer user, and investor profiles owned by investor users,
 * optionally approved for the need. Call cleanUp() after each test.
 */
public final class Deals {

    public final UUID issuerUser = UUID.randomUUID();
    public final UUID issuerId;
    public final UUID needId;
    private final JdbcTemplate db;
    private final java.util.List<UUID> investors = new java.util.ArrayList<>();

    public record Party(UUID user, UUID investorId, UUID matchResultId) {}

    public Deals(JdbcTemplate db, String instrument) {
        this.db = db;
        issuerId = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Deal Co " + UUID.randomUUID(), issuerUser);
        needId = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,"
                + "remaining_capacity) VALUES (?,'Deal','d','FINTECH','EUROPE',10000000,?,'MEDIUM',10000000) RETURNING id",
                UUID.class, issuerId, instrument);
    }

    /** An investor user with a profile (mandate 100k-2M). runStatus null = never matched to this need. */
    public Party investor(String runStatus) {
        UUID user = UUID.randomUUID();
        UUID id = db.queryForObject("INSERT INTO investors(name,investor_type,region,owner_user_id,contact_name,role_title)"
                + " VALUES (?,'VC','EUROPE',?,'Ivy','Managing Partner') RETURNING id", UUID.class, "Deal Fund " + UUID.randomUUID(), user);
        investors.add(id);
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'FINTECH','EUROPE',100000,2000000,'MEDIUM','{EQUITY,DEBT}')", id);
        if (runStatus == null) {
            return new Party(user, id, null);
        }
        return new Party(user, id, match(id, runStatus));
    }

    /** Ranks an existing investor for this need in a run with the given status. Returns the match result id. */
    public UUID match(UUID investorId, String runStatus) {
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id,status,reviewed_at) VALUES (?,?,?,now()) RETURNING id",
                UUID.class, needId, UUID.randomUUID(), runStatus);
        return db.queryForObject("INSERT INTO match_results(run_id,investor_id,rank,score,explanation,deal_brief)"
                + " VALUES (?,?,1,80,'x','brief') RETURNING id", UUID.class, run, investorId);
    }

    public void cleanUp() {
        db.update("DELETE FROM idempotency_keys WHERE idem_key LIKE 'deal-%'");
        db.update("DELETE FROM commitment_events WHERE need_id = ?", needId);
        db.update("DELETE FROM commitments WHERE need_id = ?", needId);
        db.update("DELETE FROM issuers WHERE id = ?", issuerId); // cascades the need, runs, results, questions
        for (UUID i : investors) db.update("DELETE FROM investors WHERE id = ?", i);
    }
}
