package com.issuermatch.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class SyntheticDataSeederTest {

    @Autowired SyntheticDataSeeder seeder;
    @Autowired JdbcTemplate db;

    long count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void createsFiftyInvestorsAndTwentyIssuersThenIsIdempotent() {
        long issuersBefore = count("issuers"); // other tests may have committed issuers
        assertThat(seeder.seed()).isTrue();
        assertThat(count("investors")).isEqualTo(50);
        assertThat(count("issuers") - issuersBefore).isEqualTo(20);
        assertThat(count("mandates")).isBetween(50L, 150L);
        assertThat(count("funding_needs")).isGreaterThanOrEqualTo(20L);

        assertThat(seeder.seed()).isFalse();
        assertThat(count("investors")).isEqualTo(50);
    }
}
