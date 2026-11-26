package com.issuermatch.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.issuermatch.common.Region;
import com.issuermatch.common.Sector;
import com.issuermatch.matching.agent.MatchingTools;
import com.issuermatch.matching.agent.ModelOutput;
import com.issuermatch.matching.embedding.Embeddings;
import com.issuermatch.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** A firm that paused deployment is in no search, gets a disqualifier and is never ranked. */
@IntegrationTest
@Transactional
class PausedInvestorTest {

    @Autowired JdbcTemplate db;
    @Autowired MatchingTools tools;
    @Autowired DeterministicMatcher fallback;
    @Autowired Embeddings embeddings;

    UUID investor(String status) {
        UUID id = db.queryForObject("INSERT INTO investors(name,investor_type,region,long_thesis_description,deployment_status)"
                + " VALUES (?,'VC','LATAM','zeppelin mooring masts',?) RETURNING id", UUID.class, "Pause " + UUID.randomUUID(), status);
        db.update("INSERT INTO mandates(investor_id,sector,region,min_ticket,max_ticket,risk_level,instruments)"
                + " VALUES (?,'ENERGY','LATAM',1,900000000,'LOW','{EQUITY}')", id);
        return id;
    }

    @Test
    void pausedInvestorsAreLeftOutEverywhereInMatching() {
        UUID active = investor("ACTIVE");
        UUID paused = investor("PAUSED");
        UUID issuer = db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'ENERGY','LATAM',?) RETURNING id",
                UUID.class, "Pause Co " + UUID.randomUUID(), UUID.randomUUID());
        UUID need = db.queryForObject("INSERT INTO funding_needs(issuer_id,title,description,sector,region,amount,instrument,"
                + "risk_level,remaining_capacity) VALUES (?,'Masts','zeppelin mooring masts','ENERGY','LATAM',1000,'EQUITY','LOW',1000)"
                + " RETURNING id", UUID.class, issuer);
        embeddings.indexPendingTheses();

        assertThat(tools.searchInvestorsByMandate(Sector.ENERGY, Region.LATAM, BigDecimal.valueOf(1000)))
                .extracting(MatchingTools.InvestorHit::investorId).contains(active).doesNotContain(paused);
        assertThat(tools.searchInvestorsByThesisSemantic(need))
                .extracting(MatchingTools.ThesisHit::investorId).contains(active).doesNotContain(paused);
        // even when asked for by id
        assertThat(tools.scoreFit(need, List.of(paused)).get(0).breakdown().disqualifiers()).singleElement().asString().contains("paused");
        assertThat(fallback.match(need).rankings()).extracting(ModelOutput.Entry::investorId).contains(active).doesNotContain(paused);
    }
}
