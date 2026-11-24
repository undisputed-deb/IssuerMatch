package com.issuermatch.issuer;

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
class IssuerApiTest {

    @Autowired MockMvc mvc;

    ResultActions create(String json) throws Exception {
        return mvc.perform(post("/issuers").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String body(String name) {
        return "{\"name\":\"" + name + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}";
    }

    String createAndGetId(String name) throws Exception {
        String res = create(body(name)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void createThenGet() throws Exception {
        String id = createAndGetId("Acme Pay");
        mvc.perform(get("/issuers/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Pay"))
                .andExpect(jsonPath("$.sector").value("FINTECH"));
    }

    @Test
    void listIsPaged() throws Exception {
        long before = Long.parseLong(mvc.perform(get("/issuers")).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"total\":(\\d+).*", "$1")); // other tests may have committed issuers
        for (int i = 0; i < 3; i++) createAndGetId("Paged " + i);
        mvc.perform(get("/issuers?size=2&page=0&sort=name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(before + 3));
    }

    @Test
    void updateChangesFields() throws Exception {
        String id = createAndGetId("Old Name");
        mvc.perform(put("/issuers/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\",\"sector\":\"ENERGY\",\"region\":\"LATAM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.sector").value("ENERGY"));
    }

    @Test
    void deleteThen404() throws Exception {
        String id = createAndGetId("Gone Inc");
        mvc.perform(delete("/issuers/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/issuers/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void duplicateNameIs409() throws Exception {
        createAndGetId("Same Name");
        create(body("Same Name")).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    void invalidInputIs400() throws Exception {
        create("{\"name\":\"\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        create("{\"name\":\"x\",\"sector\":\"CRYPTO\",\"region\":\"EUROPE\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());
        create("{\"name\":\"" + "x".repeat(201) + "\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownJsonFieldIsRejected() throws Exception {
        create("{\"name\":\"x\",\"sector\":\"FINTECH\",\"region\":\"EUROPE\",\"ownerUserId\":\"" + UUID.randomUUID() + "\",\"isAdmin\":true}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void badIdAndMissingId() throws Exception {
        mvc.perform(get("/issuers/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/issuers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
