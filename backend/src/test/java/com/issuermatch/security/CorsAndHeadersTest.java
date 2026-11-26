package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class CorsAndHeadersTest {

    static final String APP = "http://localhost:5173";

    @Autowired MockMvc mvc;

    @Test
    void theFrontendOriginPassesPreflight() throws Exception {
        mvc.perform(options("/commitments").header("Origin", APP).header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key").with(Auth.anonymous()))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", APP))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void everyOtherOriginIsRefused() throws Exception {
        for (String evil : new String[] {"https://evil.example", "http://localhost:5174", "http://localhost:5173.evil.example", "null"}) {
            mvc.perform(options("/issuers").header("Origin", evil).header("Access-Control-Request-Method", "GET").with(Auth.anonymous()))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(get("/issuers").header("Origin", evil))
                    .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void securityHeadersAreOnApiResponses() throws Exception {
        mvc.perform(get("/issuers").secure(true))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    void errorResponsesCarryTheHeadersToo() throws Exception {
        mvc.perform(get("/issuers").with(Auth.anonymous()))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void aWildcardOriginCannotBeConfigured() {
        SecurityConfig config = new SecurityConfig();
        assertThatThrownBy(() -> config.corsConfigurationSource("*")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.corsConfigurationSource("https://*.example.com")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.corsConfigurationSource(" ")).isInstanceOf(IllegalStateException.class);
    }
}
