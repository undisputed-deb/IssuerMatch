package com.issuermatch.fundingneed;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class FundingNeedApiTest {

    @Autowired MockMvc mvc;

    String issuer() throws Exception {
        String res = mvc.perform(post("/issuers").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"name\":\"Iss " + UUID.randomUUID() + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ownerUserId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    ResultActions create(String issuerId, String amount, String description) throws Exception {
        return mvc.perform(post("/funding-needs").contentType(MediaType.APPLICATION_JSON).content(
                "{\"issuerId\":\"" + issuerId + "\",\"title\":\"Series A\",\"description\":\"" + description
                        + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"amount\":" + amount
                        + ",\"instrument\":\"EQUITY\",\"riskLevel\":\"MEDIUM\"}"));
    }

    @Test
    void createSetsStatusAndFullCapacity() throws Exception {
        create(issuer(), "5000000", "Raising for expansion").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.remainingCapacity").value(5000000))
                .andExpect(jsonPath("$.amount").value(5000000));
    }

    @Test
    void unknownIssuerIs404() throws Exception {
        create(UUID.randomUUID().toString(), "100", "x").andExpect(status().isNotFound());
    }

    @Test
    void validationRejectsBadAmountAndHugeDescription() throws Exception {
        String iss = issuer();
        create(iss, "0", "x").andExpect(status().isBadRequest());
        create(iss, "-5", "x").andExpect(status().isBadRequest());
        create(iss, "100", "x".repeat(5001)).andExpect(status().isBadRequest());
        create(iss, "100", "").andExpect(status().isBadRequest());
    }

    @Test
    void listCanFilterByIssuer() throws Exception {
        String a = issuer();
        String b = issuer();
        create(a, "100", "one");
        create(a, "200", "two");
        create(b, "300", "three");
        mvc.perform(get("/funding-needs?issuerId=" + a)).andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/funding-needs?issuerId=" + b)).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void getUnknownIs404() throws Exception {
        mvc.perform(get("/funding-needs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
