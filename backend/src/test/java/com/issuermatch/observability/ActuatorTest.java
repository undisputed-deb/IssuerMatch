package com.issuermatch.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.common.AppMetrics;
import com.issuermatch.support.Auth;
import com.issuermatch.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ActuatorTest {

    @Autowired MockMvc mvc;
    @Autowired AppMetrics metrics;

    @Test
    void healthIsPublicAndRevealsNothingElse() throws Exception {
        String body = mvc.perform(get("/actuator/health").with(Auth.anonymous())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("components").doesNotContain("database").doesNotContain("diskSpace");
    }

    @Test
    void metricsAreAdminOnly() throws Exception {
        mvc.perform(get("/actuator/metrics").with(Auth.anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(Auth.analyst())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(Auth.issuer(UUID.randomUUID()))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(Auth.admin())).andExpect(status().isOk());
    }

    @Test
    void dangerousEndpointsAreNotExposedEvenToAdmins() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/heapdump", "/actuator/threaddump", "/actuator/beans",
                "/actuator/configprops", "/actuator/loggers", "/actuator/shutdown"}) {
            mvc.perform(get(path).with(Auth.admin())).andExpect(status().isNotFound());
        }
    }

    @Test
    void businessMetricsAreRecorded() throws Exception {
        metrics.matchFinished("PENDING_REVIEW", Duration.ofMillis(1500), 100, 20);
        metrics.commitmentDecided("ACCEPTED");
        for (String name : new String[] {"issuermatch.match.latency", "issuermatch.llm.tokens", "issuermatch.commitments",
                "issuermatch.outbox.backlog"}) {
            mvc.perform(get("/actuator/metrics/" + name).with(Auth.admin())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.measurements[0].value").isNumber());
        }
        mvc.perform(get("/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max").with(Auth.admin())).andExpect(status().isOk());
    }
}
