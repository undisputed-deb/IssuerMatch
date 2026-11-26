package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.common.Region;
import com.issuermatch.common.Sector;
import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class SearchInvestorsToolTest {

    @Autowired MatchingTools tools;
    @Autowired JdbcTemplate db;

    UUID investor(String name) {
        return db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, name + UUID.randomUUID());
    }

    void mandate(UUID inv, String sector, String region, long min, long max) {
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,?,?,?,?,'LOW','{EQUITY,DEBT}')", inv, sector, region, min, max);
    }

    List<UUID> search(long ticket) {
        return tools.searchInvestorsByMandate(Sector.FINTECH, Region.EUROPE, BigDecimal.valueOf(ticket))
                .stream().map(MatchingTools.InvestorHit::investorId).toList();
    }

    @Test
    void returnsOnlyInvestorsWhoseMandateMatchesSectorRegionAndTicket() {
        UUID fit = investor("fit");
        mandate(fit, "FINTECH", "EUROPE", 500_000, 2_000_000);
        UUID wrongSector = investor("sector");
        mandate(wrongSector, "ENERGY", "EUROPE", 500_000, 2_000_000);
        UUID wrongRegion = investor("region");
        mandate(wrongRegion, "FINTECH", "LATAM", 500_000, 2_000_000);
        UUID tooSmall = investor("small");
        mandate(tooSmall, "FINTECH", "EUROPE", 5_000_000, 9_000_000);

        assertThat(search(1_000_000)).contains(fit).doesNotContain(wrongSector, wrongRegion, tooSmall);
    }

    @Test
    void ticketBoundariesAreInclusive() {
        UUID inv = investor("edge");
        mandate(inv, "FINTECH", "EUROPE", 1_234_567, 7_654_321);
        assertThat(search(1_234_567)).contains(inv);
        assertThat(search(7_654_321)).contains(inv);
        assertThat(search(1_234_566)).doesNotContain(inv);
        assertThat(search(7_654_322)).doesNotContain(inv);
    }

    @Test
    void investorWithTwoMatchingMandatesAppearsOnce() {
        UUID inv = investor("two");
        mandate(inv, "FINTECH", "EUROPE", 100, 900_000_000);
        mandate(inv, "FINTECH", "EUROPE", 200, 800_000_000);
        assertThat(search(5_000_000).stream().filter(inv::equals).count()).isEqualTo(1);
    }

    @Test
    void resultIsCappedAtTwenty() {
        for (int i = 0; i < 30; i++) mandate(investor("many" + i), "FINTECH", "EUROPE", 100, 900_000_000);
        assertThat(search(5_000_000)).hasSize(MatchingTools.MAX_RESULTS);
    }

    @Test
    void rejectsNonPositiveTicketAndNulls() {
        assertThatThrownBy(() -> tools.searchInvestorsByMandate(Sector.FINTECH, Region.EUROPE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.searchInvestorsByMandate(null, Region.EUROPE, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
