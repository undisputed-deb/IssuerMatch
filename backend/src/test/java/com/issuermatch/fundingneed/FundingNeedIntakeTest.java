package com.issuermatch.fundingneed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** The richer intake: currency, stage, use of funds, hard constraints and instrument-specific terms. */
@IntegrationTest
@Transactional
class FundingNeedIntakeTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired jakarta.persistence.EntityManager em;

    UUID issuer() {
        return db.queryForObject("INSERT INTO issuers(name,sector,region,owner_user_id) VALUES (?,'FINTECH','EUROPE',?) RETURNING id",
                UUID.class, "Intake " + UUID.randomUUID(), UUID.randomUUID());
    }

    ResultActions submit(String instrument, String extra) throws Exception {
        return mvc.perform(post("/funding-needs").contentType(MediaType.APPLICATION_JSON).content("{\"issuerId\":\"" + issuer()
                + "\",\"title\":\"t\",\"description\":\"d\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":1000000,"
                + "\"instrument\":\"" + instrument + "\",\"riskLevel\":\"LOW\"" + (extra.isEmpty() ? "" : "," + extra) + "}"));
    }

    String readBack(ResultActions created) throws Exception {
        String id = created.andReturn().getResponse().getContentAsString().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1");
        em.flush();
        em.clear();
        return mvc.perform(get("/funding-needs/" + id)).andReturn().getResponse().getContentAsString();
    }

    static final String EQUITY = "\"instrumentTerms\":{\"type\":\"EQUITY\",\"targetValuation\":20000000,\"arrUsd\":1500000,\"yoyGrowthPct\":80,\"boardSeat\":true}";
    static final String DEBT = "\"instrumentTerms\":{\"type\":\"DEBT\",\"tenorMonths\":36,\"seniority\":\"SENIOR_SECURED\",\"collateralType\":\"receivables\",\"targetYieldPct\":11.5,\"ebitdaUsd\":900000}";
    static final String CONVERTIBLE = "\"instrumentTerms\":{\"type\":\"CONVERTIBLE\",\"valuationCap\":15000000,\"discountPct\":20,\"interestRatePct\":6}";

    @Test
    void oldStyleRequestsStillWorkWithDefaults() throws Exception {
        submit("EQUITY", "").andExpect(status().isAccepted()).andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.useOfFunds").isEmpty()).andExpect(jsonPath("$.instrumentTerms").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void allTheNewFieldsRoundTripThroughTheDatabase() throws Exception {
        String extra = "\"currency\":\"EUR\",\"companyStage\":\"SERIES_A\",\"targetCloseDate\":\"" + LocalDate.now().plusMonths(3)
                + "\",\"useOfFunds\":[\"Hire 10 engineers\",\"Expand to Spain\"],\"minTicketSize\":250000,"
                + "\"hardConstraints\":{\"allowedInvestorRegions\":[\"EUROPE\"],\"excludedInvestorTypes\":[\"CORPORATE_VC\"],"
                + "\"requireInstrumentMatch\":true,\"minInvestorDeployableUsd\":5000000}," + EQUITY;
        String body = readBack(submit("EQUITY", extra).andExpect(status().isAccepted()));
        assertThat(body).contains("\"currency\":\"EUR\"", "\"companyStage\":\"SERIES_A\"", "Hire 10 engineers", "\"minTicketSize\":250000",
                "\"type\":\"EQUITY\"", "\"boardSeat\":true", "\"yoyGrowthPct\":80", "CORPORATE_VC", "\"requireInstrumentMatch\":true");
        assertThat(db.queryForObject("SELECT instrument_terms->>'type' FROM funding_needs WHERE currency = 'EUR' AND company_stage = 'SERIES_A' LIMIT 1",
                String.class)).isEqualTo("EQUITY");
    }

    @Test
    void eachInstrumentAcceptsItsOwnTerms() throws Exception {
        assertThat(readBack(submit("DEBT", DEBT).andExpect(status().isAccepted()))).contains("\"type\":\"DEBT\"", "SENIOR_SECURED", "\"tenorMonths\":36");
        submit("MEZZANINE", DEBT).andExpect(status().isAccepted());
        assertThat(readBack(submit("CONVERTIBLE", CONVERTIBLE).andExpect(status().isAccepted()))).contains("\"type\":\"CONVERTIBLE\"", "\"valuationCap\":15000000");
    }

    @Test
    void termsMustMatchTheInstrument() throws Exception {
        submit("EQUITY", DEBT).andExpect(status().isBadRequest());
        submit("DEBT", EQUITY).andExpect(status().isBadRequest());
        submit("CONVERTIBLE", EQUITY).andExpect(status().isBadRequest());
        submit("DEBT", CONVERTIBLE).andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrHostileTermsAreRefused() throws Exception {
        for (String bad : new String[] {
                "\"instrumentTerms\":{\"type\":\"WARRANT\"}", "\"instrumentTerms\":{}", "\"instrumentTerms\":[]", "\"instrumentTerms\":\"EQUITY\"",
                "\"instrumentTerms\":{\"type\":\"EQUITY\",\"nope\":1}", "\"instrumentTerms\":{\"type\":\"EQUITY\",\"tenorMonths\":5}",
                "\"instrumentTerms\":{\"type\":\"java.lang.Runtime\"}", "\"instrumentTerms\":{\"@class\":\"x\",\"type\":\"EQUITY\"}"}) {
            submit("EQUITY", bad).andExpect(status().isBadRequest());
        }
        submit("DEBT", DEBT.replace("\"tenorMonths\":36", "\"tenorMonths\":0")).andExpect(status().isBadRequest());
        submit("DEBT", DEBT.replace("\"targetYieldPct\":11.5", "\"targetYieldPct\":500")).andExpect(status().isBadRequest());
        submit("CONVERTIBLE", CONVERTIBLE.replace("\"discountPct\":20", "\"discountPct\":150")).andExpect(status().isBadRequest());
    }

    @Test
    void theOtherNewFieldsAreValidated() throws Exception {
        for (String bad : new String[] {
                "\"currency\":\"XXX\"", "\"currency\":\"usd\"", "\"companyStage\":\"IPO\"", "\"targetCloseDate\":\"2020-01-01\"",
                "\"targetCloseDate\":\"soon\"", "\"minTicketSize\":2000000", "\"minTicketSize\":0", "\"useOfFunds\":[\"\"]",
                "\"useOfFunds\":\"hire\"", "\"useOfFunds\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\"]",
                "\"hardConstraints\":{\"unknown\":true}", "\"hardConstraints\":{\"allowedInvestorRegions\":[\"MARS\"]}"}) {
            submit("EQUITY", bad).andExpect(status().isBadRequest());
        }
    }
}
