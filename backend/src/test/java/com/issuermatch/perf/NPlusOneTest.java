package com.issuermatch.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.seed.SyntheticDataSeeder;
import com.issuermatch.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** GET /investors returns each investor with its mandates. Count the SQL statements it takes. */
@IntegrationTest
@Transactional
class NPlusOneTest {

    @Autowired MockMvc mvc;
    @Autowired SyntheticDataSeeder seeder;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    @Test
    void investorListUsesAConstantNumberOfQueries() throws Exception {
        seeder.seed();
        em.flush();
        em.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        mvc.perform(get("/investors?size=50")).andExpect(status().isOk());

        long statements = stats.getPrepareStatementCount();
        System.out.println(">>> GET /investors?size=50 ran " + statements + " SQL statements");
        // page query + count query + ONE batched mandates query. Without batching this is 52.
        assertThat(statements).isLessThanOrEqualTo(3);
    }
}
