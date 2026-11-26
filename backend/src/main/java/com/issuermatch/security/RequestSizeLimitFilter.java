package com.issuermatch.security;

import com.issuermatch.common.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses oversized request bodies before anyone parses them (the largest legitimate body is a 5,000 character
 * description). A declared Content-Length is rejected up front; a chunked body is cut off once it passes the limit.
 */
@Component
@Order(-90) // right after the security chain (-100), so the 413 answer still carries CORS and security headers
class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RequestSizeLimitFilter(@Value("${app.http.max-body-bytes:65536}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body is too large\"}}");
            return;
        }
        chain.doFilter(new Limited(request, maxBytes), response);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long maxBytes;

        Limited(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private int count(int n) throws IOException {
                    if (n > 0 && (read += n) > maxBytes) {
                        throw new PayloadTooLargeException();
                    }
                    return n;
                }

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    count(b < 0 ? 0 : 1);
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return count(in.read(b, off, len));
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
