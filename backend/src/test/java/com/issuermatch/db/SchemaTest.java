package com.issuermatch.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Proves the database itself rejects bad data, whatever the API does. */
@IntegrationTest
@Transactional
class SchemaTest {

    @Autowired JdbcTemplate db;

    /** Runs a statement that must be rejected. Savepoint keeps the surrounding transaction usable. */
    void rejected(Runnable statement) {
        db.execute("SAVEPOINT s");
        try {
            assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            db.execute("ROLLBACK TO SAVEPOINT s");
        }
    }

    UUID investor() {
        return db.queryForObject(
                "INSERT INTO investors(name,type,region) VALUES (?,?,?) RETURNING id", UUID.class,
                "Inv " + UUID.randomUUID(), "VC", "EUROPE");
    }

    UUID issuer() {
        return db.queryForObject(
                "INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,?,?,?) RETURNING id", UUID.class,
                "Iss " + UUID.randomUUID(), "FINTECH", "EUROPE", UUID.randomUUID());
    }

    UUID need(UUID issuerId, String amount, String remaining) {
        return db.queryForObject(
                "INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                        + " VALUES (?,?,?,?,?,?::numeric,?,?,?::numeric) RETURNING id", UUID.class,
                issuerId, "t", "d", "FINTECH", "EUROPE", amount, "EQUITY", "LOW", remaining);
    }

    void mandate(UUID investorId, String min, String max, String sector, String instruments) {
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,?,?,?::numeric,?::numeric,?,?::text[])",
                investorId, sector, "EUROPE", min, max, "LOW", instruments);
    }

    @Test
    void validRowsInsert() {
        UUID inv = investor();
        mandate(inv, "100000", "500000", "FINTECH", "{EQUITY,DEBT}");
        need(issuer(), "1000000", "1000000");
    }

    @Test
    void mandateRejectsMinAboveMax() {
        UUID inv = investor();
        rejected(() -> mandate(inv, "500000", "100000", "FINTECH", "{EQUITY}"));
    }

    @Test
    void mandateRejectsUnknownSectorAndInstrument() {
        UUID inv = investor();
        rejected(() -> mandate(inv, "1", "2", "CRYPTO", "{EQUITY}"));
    }

    @Test
    void mandateRejectsBadInstrumentList() {
        UUID inv = investor();
        rejected(() -> mandate(inv, "1", "2", "FINTECH", "{EQUITY,MEMECOIN}"));
        rejected(() -> mandate(inv, "1", "2", "FINTECH", "{}"));
    }

    @Test
    void needRejectsNonPositiveAmountAndOverCapacity() {
        UUID iss = issuer();
        rejected(() -> need(iss, "0", "0"));
        rejected(() -> need(iss, "100", "101"));
        rejected(() -> need(iss, "100", "-1"));
    }

    @Test
    void needRejectsOversizedDescription() {
        UUID iss = issuer();
        rejected(() -> db.update(
                "INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,remaining_capacity)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                iss, "t", "x".repeat(5001), "FINTECH", "EUROPE", 1, "EQUITY", "LOW", 1));
    }

    @Test
    void sameEventCannotCreateTwoRuns() {
        UUID n = need(issuer(), "100", "100");
        UUID event = UUID.randomUUID();
        db.update("INSERT INTO match_runs(need_id,event_id) VALUES (?,?)", n, event);
        rejected(() -> db.update("INSERT INTO match_runs(need_id,event_id) VALUES (?,?)", n, event));
    }

    @Test
    void resultsRejectBadScoreDuplicateInvestorAndDuplicateRank() {
        UUID n = need(issuer(), "100", "100");
        UUID run = db.queryForObject("INSERT INTO match_runs(need_id,event_id) VALUES (?,?) RETURNING id",
                UUID.class, n, UUID.randomUUID());
        UUID a = investor();
        UUID b = investor();
        String sql = "INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,?,?,?)";
        db.update(sql, run, a, 1, 80, "ok");
        rejected(() -> db.update(sql, run, b, 2, 101, "x"));
        rejected(() -> db.update(sql, run, a, 2, 50, "x"));
        rejected(() -> db.update(sql, run, b, 1, 50, "x"));
    }

    @Test
    void everyTableHasRowLevelSecurityOn() {
        List<String> exposed = db.queryForList(
                "SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r'"
                        + " AND NOT relrowsecurity AND relname <> 'flyway_schema_history'", String.class);
        assertThat(exposed).isEmpty();
    }

    @Test
    void mainIndexesExist() {
        List<String> idx = db.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='public'", String.class);
        assertThat(idx).contains("idx_mandates_match", "idx_issuers_owner", "idx_needs_issuer_created", "idx_audit_actor_at");
    }
}
