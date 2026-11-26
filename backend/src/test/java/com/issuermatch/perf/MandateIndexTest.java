package com.issuermatch.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Shows what idx_mandates_match buys on the main matching query, with 200k fake mandates. */
@IntegrationTest
@Transactional
class MandateIndexTest {

    static final String QUERY = "SELECT investor_id FROM mandates"
            + " WHERE sector = 'FINTECH' AND region = 'EUROPE' AND min_ticket <= 2000000 AND max_ticket >= 2000000";

    @Autowired JdbcTemplate db;

    String plan() {
        List<String> lines = db.queryForList("EXPLAIN (ANALYZE, COSTS OFF) " + QUERY, String.class);
        return String.join("\n", lines);
    }

    @Test
    void indexIsUsedAndFasterThanSeqScan() {
        db.update("INSERT INTO investors(name,investor_type,region) SELECT 'Inv ' || g, 'VC', 'EUROPE' FROM generate_series(1,2000) g");
        db.update("""
                INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)
                SELECT (SELECT id FROM investors WHERE name = 'Inv ' || (1 + (g % 2000))),
                       (ARRAY['TECHNOLOGY','FINTECH','HEALTHCARE','ENERGY','REAL_ESTATE','CONSUMER','INDUSTRIALS'])[1 + (g % 7)],
                       (ARRAY['NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA'])[1 + ((g / 7) % 5)],
                       250000 * (1 + (g % 20)), 250000 * (1 + (g % 20)) * 4, 'LOW', ARRAY['EQUITY']
                FROM generate_series(1,200000) g
                """);
        db.execute("ANALYZE mandates");

        String withIndex = plan();
        db.execute("DROP INDEX idx_mandates_match"); // DDL is transactional in Postgres: rolled back after the test
        String withoutIndex = plan();

        System.out.println(">>> WITH idx_mandates_match:\n" + withIndex);
        System.out.println(">>> WITHOUT index:\n" + withoutIndex);
        assertThat(withIndex).contains("idx_mandates_match");
        assertThat(withoutIndex).contains("Seq Scan");
    }
}
