package com.issuermatch.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PaginationTest {

    @Autowired MockMvc mvc;

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/issuers?size=100000")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void defaultsApply() throws Exception {
        mvc.perform(get("/investors")).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void unknownSortPropertyIs400NotAServerError() throws Exception {
        for (String url : new String[] {"/issuers", "/investors", "/funding-needs"}) {
            mvc.perform(get(url + "?sort=password,desc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void negativeOrGarbagePageFallsBackSafely() throws Exception {
        mvc.perform(get("/issuers?page=-3&size=-1")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0));
        mvc.perform(get("/issuers?page=abc")).andExpect(status().isOk());
    }
}
