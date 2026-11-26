package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
@TestPropertySource(properties = {"app.rate-limit.enabled=true", "app.rate-limit.ip-per-minute=20",
        "app.rate-limit.user-per-minute=5", "app.rate-limit.llm-per-minute=2", "app.rate-limit.llm-global-per-minute=3"})
class RateLimitTest {

    @Autowired MockMvc mvc;
    @Autowired RateLimiter limiter;

    /** Each test uses its own address so the per-IP buckets do not interfere. */
    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    int code(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void aUserIsCutOffAfterTheLimitWithRetryAfterAndOthersAreNot() throws Exception {
        UUID noisy = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/issuers").with(Auth.issuer(noisy)).with(from("10.1.0.1"))).andExpect(status().isOk());
        }
        mvc.perform(get("/issuers").with(Auth.issuer(noisy)).with(from("10.1.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"));
        mvc.perform(get("/issuers").with(Auth.issuer(UUID.randomUUID())).with(from("10.1.0.2"))).andExpect(status().isOk());
    }

    @Test
    void changingAddressDoesNotResetAUsersLimit() throws Exception {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < 5; i++) code(get("/issuers").with(Auth.issuer(user)).with(from("10.2.0." + i)));
        assertThat(code(get("/issuers").with(Auth.issuer(user)).with(from("10.2.0.99")))).isEqualTo(429);
    }

    @Test
    void anonymousHammeringIsLimitedByAddressAndAForwardedHeaderDoesNotHelp() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(code(get("/issuers").with(Auth.anonymous()).with(from("10.3.0.1"))
                    .header("X-Forwarded-For", "1.2.3." + i))).isEqualTo(401);
        }
        assertThat(code(get("/issuers").with(Auth.anonymous()).with(from("10.3.0.1")).header("X-Forwarded-For", "9.9.9.9")))
                .isEqualTo(429);
    }

    @Test
    void theEndpointsThatCostMoneyHaveATighterLimitPerUserAndOverall() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        // invalid body on purpose: the limit applies before any work happens
        assertThat(code(llm(alice, "/funding-needs"))).isEqualTo(400);
        assertThat(code(llm(alice, "/funding-needs/" + UUID.randomUUID() + "/match"))).isEqualTo(404);
        assertThat(code(llm(alice, "/funding-needs"))).isEqualTo(429);               // 3rd call: over alice's 2
        assertThat(code(get("/issuers").with(Auth.issuer(alice)).with(from("10.4.0.1")))).isEqualTo(200); // other calls fine
        assertThat(code(llm(bob, "/funding-needs"))).isEqualTo(400);                 // 3rd model call overall
        assertThat(code(llm(bob, "/funding-needs"))).isEqualTo(429);                 // over the shared ceiling of 3
    }

    MockHttpServletRequestBuilder llm(UUID user, String url) {
        return post(url).with(Auth.issuer(user)).with(from("10.4.0.1")).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @Test
    void healthChecksAreNeverLimited() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(code(get("/health").with(Auth.anonymous()).with(from("10.5.0.1")))).isEqualTo(200);
        }
    }

    @Test
    void theBucketMapIsBoundedSoMadeUpKeysCannotExhaustMemory() {
        for (int i = 0; i < RateLimiter.MAX_KEYS + 500; i++) limiter.tryAcquire("flood:" + i, 5);
        assertThat(limiter.size()).isEqualTo(RateLimiter.MAX_KEYS);
    }
}
