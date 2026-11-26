package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.common.Sector;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class MarketAndScoreToolsTest {

    @Autowired MatchingTools tools;
    @Autowired JdbcTemplate db;

    UUID need() {
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "S " + UUID.randomUUID(), UUID.randomUUID());
        return db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'t','d','FINTECH','EUROPE',1000000,'EQUITY','MEDIUM',1000000) RETURNING id",
                UUID.class, issuer);
    }

    UUID investorWithMandates(String... mandateValues) { // each: sector,region,min,max,risk
        UUID inv = db.queryForObject("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE') RETURNING id",
                UUID.class, "I " + UUID.randomUUID());
        for (String m : mandateValues) {
            String[] p = m.split(",");
            db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                    + " VALUES (?,?,?,?::numeric,?::numeric,?,'{EQUITY}')", inv, p[0], p[1], p[2], p[3], p[4]);
        }
        return inv;
    }

    @Test
    void everySectorHasMarketNotes() {
        for (Sector s : Sector.values()) {
            assertThat(tools.getMarketContext(s).note()).isNotBlank();
            assertThat(tools.getMarketContext(s).sector()).isEqualTo(s);
        }
        assertThatThrownBy(() -> tools.getMarketContext(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scoreFitMatchesThePureScorer() {
        UUID need = need();
        UUID perfect = investorWithMandates("FINTECH,EUROPE,500000,2000000,MEDIUM");
        MatchingTools.FitResult r = tools.scoreFit(need, perfect);
        assertThat(r.total().doubleValue()).isEqualTo(100.0);
        assertThat(r.ticketPoints().doubleValue()).isEqualTo(30.0);
    }

    @Test
    void scoreFitUsesTheBestMandate() {
        UUID need = need();
        UUID inv = investorWithMandates("ENERGY,LATAM,5000000,9000000,HIGH", "FINTECH,EUROPE,500000,2000000,MEDIUM");
        assertThat(tools.scoreFit(need, inv).total().doubleValue()).isEqualTo(100.0);
    }

    @Test
    void scoreFitIsRepeatable() {
        UUID need = need();
        UUID inv = investorWithMandates("FINTECH,LATAM,2000000,4000000,LOW");
        assertThat(tools.scoreFit(need, inv)).isEqualTo(tools.scoreFit(need, inv));
    }

    @Test
    void scoreFitRejectsUnknownIdsAndInvestorsWithoutMandates() {
        UUID need = need();
        assertThatThrownBy(() -> tools.scoreFit(UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(need, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(need, investorWithMandates())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
