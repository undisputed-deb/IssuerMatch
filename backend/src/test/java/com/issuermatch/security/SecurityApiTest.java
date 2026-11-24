package com.issuermatch.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class SecurityApiTest {

    @Autowired MockMvc mvc;

    static final UUID ID = UUID.randomUUID();

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/health").with(Auth.anonymous())).andExpect(status().isOk());
    }

    @Test
    void everythingElseNeedsAToken() throws Exception {
        MockHttpServletRequestBuilder[] requests = {
                get("/issuers"), post("/issuers"), get("/issuers/" + ID), put("/issuers/" + ID), delete("/issuers/" + ID),
                get("/investors"), post("/investors"), get("/investors/" + ID), get("/investors/" + ID + "/mandates"),
                get("/funding-needs"), post("/funding-needs"), get("/funding-needs/" + ID),
                get("/match-runs"), get("/match-runs/" + ID), get("/match-runs/" + ID + "/results"),
                post("/match-runs/" + ID + "/approve"), post("/match-runs/" + ID + "/reject"),
                post("/commitments"),
                get("/does-not-exist"), get("/actuator/env")};
        for (MockHttpServletRequestBuilder r : requests) {
            mvc.perform(r.contentType(MediaType.APPLICATION_JSON).content("{}").with(Auth.anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void aGarbageBearerTokenIs401WithoutSayingWhy() throws Exception {
        for (String token : new String[] {"garbage", "a.b.c", "eyJhbGciOiJub25lIn0.e30.", ""}) {
            mvc.perform(get("/issuers").header("Authorization", "Bearer " + token).with(Auth.anonymous()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.message").value("Authentication required"));
        }
    }

    @Test
    void otherAuthSchemesAreNotAccepted() throws Exception {
        mvc.perform(get("/issuers").header("Authorization", "Basic YWRtaW46YWRtaW4=").with(Auth.anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersAreSentOnEveryResponse() throws Exception {
        mvc.perform(get("/health").with(Auth.anonymous()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }
}
