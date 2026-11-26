package com.issuermatch.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import com.issuermatch.support.IntegrationTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(GlobalExceptionHandlerTest.Probe.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;

    record Body(@NotBlank String name) {}

    @RestController
    static class Probe {
        @GetMapping("/probe/boom") String boom() { throw new IllegalStateException("secret SQL detail"); }
        @GetMapping("/probe/db-down") String dbDown() { throw new org.springframework.transaction.CannotCreateTransactionException("jdbc secret host"); }
        @GetMapping("/probe/db-lost") String dbLost() { throw new org.springframework.dao.DataAccessResourceFailureException("connection to 10.0.0.1 refused"); }
        @GetMapping("/probe/db-dropped") String dbDropped() {
            throw new org.springframework.orm.jpa.JpaSystemException(new RuntimeException("Unable to rollback",
                    new java.sql.SQLException("This connection has been closed.", "08003")));
        }
        @GetMapping("/probe/db-shutdown") String dbShutdown() {
            throw new RuntimeException(new java.sql.SQLException("terminating connection due to administrator command", "57P01"));
        }
        @GetMapping("/probe/db-pool-closed") String poolClosed() { // the exact shape chaos.sh produced
            throw new org.springframework.orm.jpa.JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
                    new java.sql.SQLException("Connection is closed")));
        }
        @GetMapping("/probe/missing") String missing() { throw new NotFoundException("Issuer not found"); }
        @PostMapping("/probe/validate") String validate(@Valid @RequestBody Body b) { return "ok"; }
    }

    @Test
    void unexpectedErrorIsGenericAndLeaksNothing() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Unexpected error"));
    }

    @Test
    void apiExceptionKeepsItsCodeAndStatus() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Issuer not found"));
    }

    @Test
    void validationFailureListsFields() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("name: must not be blank"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownRouteIsFormattedNotFound() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(post("/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void anUnreachableDatabaseIs503WithRetryAfterAndNoDetails() throws Exception {
        for (String path : new String[] {"/probe/db-down", "/probe/db-lost", "/probe/db-dropped", "/probe/db-shutdown", "/probe/db-pool-closed"}) {
            mvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message").value("Service temporarily unavailable"));
        }
    }
}
