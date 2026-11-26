package com.issuermatch.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** scripts/e2e-cleanup.sql deletes rows, so it is tested: everything E2E goes, everything else stays consistent. */
@IntegrationTest
class E2eCleanupSqlTest {

    @Autowired JdbcTemplate db;

    UUID realIssuer;
    UUID realInvestor;

    @AfterEach
    void cleanUp() {
        db.update("DELETE FROM commitment_events WHERE investor_id = ?", realInvestor);
        db.update("DELETE FROM commitments WHERE investor_id = ?", realInvestor);
        db.update("DELETE FROM issuers WHERE id = ?", realIssuer);
        db.update("DELETE FROM investors WHERE id = ?", realInvestor);
    }

    @Test
    void removesTestRowsAndEverythingHangingOffThemAndNothingElse() throws IOException {
        realIssuer = issuer("Keeper Co");
        realInvestor = investor("Keeper Fund");
        UUID realNeed = need(realIssuer, "Keeper raise", 700); // 100 held by the keeper, 200 by the test investor
        UUID testIssuer = issuer("E2E Co 1");
        UUID testInvestor = investor("E2E Fund 1");
        UUID testNeed = need(testIssuer, "E2E deal 1", 950);
        UUID testNeedOnRealIssuer = need(realIssuer, "E2E browser 1", 1000);

        UUID keptCommitment = commitment(realNeed, realInvestor, 100);
        commitment(realNeed, testInvestor, 200);
        commitment(testNeed, testInvestor, 50);
        UUID realRun = run(realNeed);
        result(realRun, realInvestor, 1);
        result(realRun, testInvestor, 2);
        result(run(testNeed), realInvestor, 1);
        db.update("INSERT INTO deal_questions(need_id,investor_id,asked_by,question) VALUES (?,?,?,'q')", realNeed, testInvestor,
                UUID.randomUUID());

        String sql = Files.readString(Path.of("..", "scripts", "e2e-cleanup.sql"));
        db.execute(sql);
        db.execute(sql); // safe to run twice

        assertThat(count("investors", "id", testInvestor)).isZero();
        assertThat(count("issuers", "id", testIssuer)).isZero();
        assertThat(count("funding_needs", "id", testNeed)).isZero();
        assertThat(count("funding_needs", "id", testNeedOnRealIssuer)).isZero();
        assertThat(count("commitments", "investor_id", testInvestor)).isZero();
        assertThat(count("commitment_events", "investor_id", testInvestor)).isZero();
        assertThat(count("match_results", "investor_id", testInvestor)).isZero();
        assertThat(count("deal_questions", "investor_id", testInvestor)).isZero();

        assertThat(count("issuers", "id", realIssuer)).isOne();
        assertThat(count("commitments", "id", keptCommitment)).isOne();
        assertThat(count("commitment_events", "commitment_id", keptCommitment)).isOne();
        assertThat(count("match_results", "run_id", realRun)).isOne();
        assertThat(db.queryForObject("SELECT remaining_capacity FROM funding_needs WHERE id = ?", BigDecimal.class, realNeed))
                .isEqualByComparingTo("900"); // the test investor's 200 came back, the keeper's 100 stays held
    }

    private UUID issuer(String name) {
        return db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, name, UUID.randomUUID());
    }

    private UUID investor(String name) {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, name);
    }

    private UUID need(UUID issuer, String title, int remaining) {
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,risk_level,"
                + "remaining_capacity) VALUES (?,?,'d','FINTECH','EUROPE',1000,'EQUITY','MEDIUM',?) RETURNING id",
                UUID.class, issuer, title, remaining);
    }

    private UUID commitment(UUID need, UUID investor, int amount) {
        UUID id = db.queryForObject("INSERT INTO commitments(need_id,investor_id,amount,status,created_by) VALUES (?,?,?,'ACCEPTED',?)"
                + " RETURNING id", UUID.class, need, investor, amount, UUID.randomUUID());
        db.update("INSERT INTO commitment_events(event_id,commitment_id,need_id,investor_id,amount,type,occurred_at)"
                + " VALUES (?,?,?,?,?,'ACCEPTED',now())", UUID.randomUUID(), id, need, investor, amount);
        return id;
    }

    private UUID run(UUID need) {
        return db.queryForObject("INSERT INTO match_runs(need_id,event_id,status) VALUES (?,?,'APPROVED') RETURNING id", UUID.class,
                need, UUID.randomUUID());
    }

    private void result(UUID run, UUID investor, int rank) {
        db.update("INSERT INTO match_results(run_id,investor_id,rank,score,explanation) VALUES (?,?,?,80,'x')", run, investor, rank);
    }

    private int count(String table, String column, UUID value) {
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
