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

    MatchingTools.FitResult one(UUID need, UUID investor) {
        return tools.scoreFit(need, java.util.List.of(investor)).get(0);
    }

    @Test
    void scoreFitReturnsTheStructuredBreakdown() {
        UUID need = need();
        UUID perfect = investorWithMandates("FINTECH,EUROPE,500000,2000000,MEDIUM");
        MatchingTools.FitResult r = one(need, perfect);
        assertThat(r.error()).isNull();
        // no past deals and no thesis: those 20 points are not earned. Stage and role unsaid: half of 10 and of 5
        assertThat(r.total().doubleValue()).isEqualTo(72.5);
        assertThat(r.breakdown().ticketFit().doubleValue()).isEqualTo(20.0);
        assertThat(r.breakdown().disqualifiers()).isEmpty();
    }

    @Test
    void scoreFitScoresManyInvestorsInOneCallUsingTheBestMandate() {
        UUID need = need();
        UUID best = investorWithMandates("ENERGY,LATAM,5000000,9000000,HIGH", "FINTECH,EUROPE,500000,2000000,MEDIUM");
        UUID weaker = investorWithMandates("FINTECH,LATAM,500000,2000000,MEDIUM");
        var results = tools.scoreFit(need, java.util.List.of(best, weaker));
        assertThat(results).extracting(MatchingTools.FitResult::investorId).containsExactly(best, weaker);
        assertThat(results.get(0).total()).isGreaterThan(results.get(1).total());
    }

    @Test
    void scoreFitIsRepeatableAndConvertsCurrencies() {
        UUID need = need(); // raises 1,000,000 USD
        UUID inv = investorWithMandates("FINTECH,EUROPE,900000,950000,MEDIUM");
        assertThat(one(need, inv)).isEqualTo(one(need, inv));
        assertThat(one(need, inv).breakdown().ticketFit().doubleValue()).isLessThan(20.0); // 950k USD is below the raise
        db.update("UPDATE investors SET currency = 'EUR' WHERE id = ?", inv);              // 972k to 1.026M USD now covers it
        assertThat(one(need, inv).breakdown().ticketFit().doubleValue()).isEqualTo(20.0);
    }

    @Test
    void scoreFitReportsUnknownIdsPerInvestorAndRejectsBadCalls() {
        UUID need = need();
        UUID ok = investorWithMandates("FINTECH,EUROPE,500000,2000000,MEDIUM");
        var mixed = tools.scoreFit(need, java.util.List.of(ok, UUID.randomUUID(), investorWithMandates()));
        assertThat(mixed.get(0).error()).isNull();
        assertThat(mixed.get(1).error()).contains("Unknown investor");
        assertThat(mixed.get(2).total()).isNull();
        assertThatThrownBy(() -> tools.scoreFit(UUID.randomUUID(), java.util.List.of(ok))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(need, java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(need, java.util.Collections.nCopies(21, ok))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.scoreFit(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
