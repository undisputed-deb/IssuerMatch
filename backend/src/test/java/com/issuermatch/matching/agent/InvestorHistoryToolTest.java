package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class InvestorHistoryToolTest {

    @Autowired MatchingTools tools;
    @Autowired JdbcTemplate db;

    UUID investor() {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'PE','EUROPE') RETURNING id",
                UUID.class, "hist " + UUID.randomUUID());
    }

    void deal(UUID inv, String issuer, int daysAgo) {
        db.update("INSERT INTO investor_deals(investor_id,issuer_name,sector,region,amount,instrument,closed_on)"
                + " VALUES (?,?,'FINTECH','EUROPE',1000000,'EQUITY', current_date - ?)", inv, issuer, daysAgo);
    }

    @Test
    void returnsNewestFirstAndAtMostTen() {
        UUID inv = investor();
        for (int i = 0; i < 12; i++) deal(inv, "Deal " + i, i * 10); // Deal 0 is the most recent
        List<MatchingTools.Deal> history = tools.getInvestorHistory(inv);
        assertThat(history).hasSize(10);
        assertThat(history.get(0).issuerName()).isEqualTo("Deal 0");
        assertThat(history.get(9).issuerName()).isEqualTo("Deal 9");
    }

    @Test
    void investorWithoutDealsGetsEmptyList() {
        assertThat(tools.getInvestorHistory(investor())).isEmpty();
    }

    @Test
    void doesNotLeakAnotherInvestorsDeals() {
        UUID a = investor();
        UUID b = investor();
        deal(a, "Only A", 5);
        assertThat(tools.getInvestorHistory(b)).isEmpty();
    }

    @Test
    void unknownOrMissingIdIsAnError() {
        assertThatThrownBy(() -> tools.getInvestorHistory(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.getInvestorHistory(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
