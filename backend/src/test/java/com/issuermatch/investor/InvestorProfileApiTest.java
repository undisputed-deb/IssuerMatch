package com.issuermatch.investor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import com.issuermatch.support.Profiles;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class InvestorProfileApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired jakarta.persistence.EntityManager em;

    final UUID me = UUID.randomUUID();

    ResultActions save(String json) throws Exception {
        return mvc.perform(put("/investors/me").with(Auth.investor(me)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String name() {
        return "Profile Fund " + UUID.randomUUID();
    }

    @Test
    void aCompleteProfileIsSavedWithItsMandatesAndMarkedComplete() throws Exception {
        String company = name();
        save(Profiles.json(company)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileCompleted").value(true))
                .andExpect(jsonPath("$.name").value(company)).andExpect(jsonPath("$.companyName").value(company))
                .andExpect(jsonPath("$.contactName").value("Ivy Chen")).andExpect(jsonPath("$.roleTitle").value("Managing Partner"))
                .andExpect(jsonPath("$.investorType").value("VC")).andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.deployableCapitalUsd").value(40000000)).andExpect(jsonPath("$.aumOrRevenueUsd").value(250000000))
                .andExpect(jsonPath("$.longThesisDescription").value(org.hamcrest.Matchers.startsWith("We back")))
                .andExpect(jsonPath("$.mandates", hasSize(1)));
        em.flush();
        em.clear();
        mvc.perform(get("/investors/me").with(Auth.investor(me))).andExpect(jsonPath("$.mandates", hasSize(1)));
    }

    @Test
    void savingAgainReplacesTheMandates() throws Exception {
        String company = name();
        save(Profiles.json(company)).andExpect(status().isOk());
        em.flush();
        em.clear();
        save(Profiles.json(company, "New thesis", Profiles.mandate("ENERGY", "LATAM", 1, 5) + "," + Profiles.mandate("CONSUMER", "MEA", 2, 6)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mandates", hasSize(2)));
        em.flush();
        em.clear();
        assertThat(db.queryForList("SELECT m.sector FROM mandates m JOIN investors i ON i.id = m.investor_id WHERE i.owner_user_id = ? ORDER BY 1",
                String.class, me)).containsExactly("CONSUMER", "ENERGY");
    }

    @Test
    void theThesisMayBeTenThousandCharactersButNoMore() throws Exception {
        save(Profiles.json(name(), "x".repeat(10_000), Profiles.mandate("FINTECH", "EUROPE", 1, 2))).andExpect(status().isOk());
        save(Profiles.json(name(), "x".repeat(10_001), Profiles.mandate("FINTECH", "EUROPE", 1, 2))).andExpect(status().isBadRequest());
    }

    @Test
    void invalidProfilesAreRefused() throws Exception {
        String good = Profiles.json(name());
        for (String[] change : new String[][] {
                {"\"contactName\":\"Ivy Chen\"", "\"contactName\":\" \""},
                {"\"currency\":\"EUR\"", "\"currency\":\"ZZZ\""},
                {"\"currency\":\"EUR\"", "\"currency\":\"eur\""},
                {"\"investorType\":\"VC\"", "\"investorType\":\"BANK\""},
                {"https://example.test/fund", "javascript:alert(1)"},
                {"https://example.test/fund", "ftp://example.test"},
                {"\"deployableCapitalUsd\":40000000", "\"deployableCapitalUsd\":0"},
                {"\"deployableCapitalUsd\":40000000", "\"deployableCapitalUsd\":-5"},
                {"\"aumOrRevenueUsd\":250000000", "\"aumOrRevenueUsd\":-1"},
                {"\"mandates\":[", "\"mandates\":[],\"x\":["},
                {"\"minTicket\":100000", "\"minTicket\":9000000"},
                {"\"roleTitle\":\"Managing Partner\",", ""}}) {
            save(good.replace(change[0], change[1])).andExpect(status().isBadRequest());
        }
        save(Profiles.json(name(), "t", String.join(",", java.util.Collections.nCopies(11, Profiles.mandate("FINTECH", "EUROPE", 1, 2)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTakenFirmNameIsAConflict() throws Exception {
        String company = name();
        db.update("INSERT INTO investors(name,investor_type,region) VALUES (?,'VC','EUROPE')", company);
        save(Profiles.json(company)).andExpect(status().isConflict());
    }

    @Test
    void staffEnteredProfilesStartIncomplete() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/investors").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name() + "\",\"investorType\":\"PRIVATE_CREDIT\",\"region\":\"EUROPE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.profileCompleted").value(false));
    }
}
