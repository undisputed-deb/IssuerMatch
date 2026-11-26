package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.issuermatch.common.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

    final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(10);

    HttpServletRequest passThrough(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    @Test
    void declaredOversizeIsRefusedBeforeTheApplicationSeesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(passThrough(request, response)).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aChunkedBodyWithNoLengthIsCutOffWhileReading() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked: no declared length
            }
        };
        request.setContent(new byte[100]);
        HttpServletRequest wrapped = passThrough(request, new MockHttpServletResponse());
        assertThatThrownBy(() -> wrapped.getInputStream().readAllBytes()).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void aBodyWithinTheLimitIsUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        request.setContent("0123456789".getBytes());
        HttpServletRequest wrapped = passThrough(request, new MockHttpServletResponse());
        assertThat(wrapped.getInputStream().readAllBytes()).hasSize(10);
    }
}
