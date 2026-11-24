package com.issuermatch.investor;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.issuermatch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class InvestorApiTest {

    @Autowired MockMvc mvc;
    @Autowired jakarta.persistence.EntityManager em;

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String json) throws Exception {
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String investor(String name) throws Exception {
        String res = send(post("/investors"), "{\"name\":\"" + name + "\",\"type\":\"VC\",\"region\":\"EUROPE\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    static String mandate(String min, String max, String instruments) {
        return "{\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"minTicket\":" + min + ",\"maxTicket\":" + max
                + ",\"riskLevel\":\"LOW\",\"instruments\":" + instruments + "}";
    }

    @Test
    void investorCrudAndEmbeddedMandates() throws Exception {
        String id = investor("Alpha Capital");
        send(post("/investors/" + id + "/mandates"), mandate("100000", "500000", "[\"EQUITY\",\"DEBT\"]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instruments", hasSize(2)));
        em.flush();
        em.clear(); // tests share one persistence context; real requests do not
        mvc.perform(get("/investors/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandates", hasSize(1)))
                .andExpect(jsonPath("$.mandates[0].minTicket").value(100000));
        send(put("/investors/" + id), "{\"name\":\"Alpha 2\",\"type\":\"PE\",\"region\":\"LATAM\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("PE"));
        mvc.perform(delete("/investors/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/investors/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void mandateValidation() throws Exception {
        String id = investor("Beta Capital");
        String url = "/investors/" + id + "/mandates";
        send(post(url), mandate("500000", "100000", "[\"EQUITY\"]")).andExpect(status().isBadRequest());
        send(post(url), mandate("0", "100", "[\"EQUITY\"]")).andExpect(status().isBadRequest());
        send(post(url), mandate("1", "2", "[]")).andExpect(status().isBadRequest());
        send(post(url), mandate("1", "2", "[\"MEMECOIN\"]")).andExpect(status().isBadRequest());
        send(post(url), mandate("1", "2", "[null]")).andExpect(status().isBadRequest());
    }

    @Test
    void mandateOfAnotherInvestorIsNotReachable() throws Exception {
        String a = investor("Gamma A");
        String b = investor("Gamma B");
        String res = send(post("/investors/" + a + "/mandates"), mandate("1", "2", "[\"EQUITY\"]"))
                .andReturn().getResponse().getContentAsString();
        String mid = res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/investors/" + b + "/mandates/" + mid)).andExpect(status().isNotFound());
        mvc.perform(delete("/investors/" + b + "/mandates/" + mid)).andExpect(status().isNotFound());
        mvc.perform(get("/investors/" + a + "/mandates/" + mid)).andExpect(status().isOk());
    }

    @Test
    void mandatesAreListedPaged() throws Exception {
        String id = investor("Delta Capital");
        for (int i = 0; i < 3; i++) send(post("/investors/" + id + "/mandates"), mandate("1", "2", "[\"EQUITY\"]"));
        mvc.perform(get("/investors/" + id + "/mandates?size=2"))
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.total").value(3));
    }
}
