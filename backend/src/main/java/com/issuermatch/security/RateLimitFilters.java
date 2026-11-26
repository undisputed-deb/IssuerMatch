package com.issuermatch.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.OptionalLong;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two filters inside the security chain (created by SecurityConfig, not component-scanned, so each runs once).
 * Per IP runs BEFORE token checking, so password-less hammering is limited too. Per user runs AFTER it.
 * The address is the socket address: X-Forwarded-For is attacker-controlled unless a trusted proxy sets it.
 */
final class RateLimitFilters {

    private RateLimitFilters() {}

    private static final RequestMatcher EXEMPT = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/health"),
            PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**"));

    /** The endpoints that cost money: each starts a model call. */
    private static final RequestMatcher LLM = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/funding-needs"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/funding-needs/{id}/match"));

    static OncePerRequestFilter perIp(RateLimiter limiter, RateLimitProperties props) {
        return new Base(props) {
            @Override
            OptionalLong check(HttpServletRequest request) {
                return limiter.tryAcquire("ip:" + request.getRemoteAddr(), props.ipPerMinute());
            }
        };
    }

    static OncePerRequestFilter perUser(RateLimiter limiter, RateLimitProperties props) {
        return new Base(props) {
            @Override
            OptionalLong check(HttpServletRequest request) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (!(auth instanceof JwtAuthenticationToken)) {
                    return OptionalLong.empty(); // not logged in: rejected by authorization, already counted per IP
                }
                OptionalLong wait = limiter.tryAcquire("user:" + auth.getName(), props.userPerMinute());
                if (wait.isEmpty() && LLM.matches(request)) {
                    wait = limiter.tryAcquire("llm:" + auth.getName(), props.llmPerMinute());
                    if (wait.isEmpty()) {
                        wait = limiter.tryAcquire("llm-global", props.llmGlobalPerMinute());
                    }
                }
                return wait;
            }
        };
    }

    private abstract static class Base extends OncePerRequestFilter {
        private final RateLimitProperties props;

        Base(RateLimitProperties props) {
            this.props = props;
        }

        abstract OptionalLong check(HttpServletRequest request);

        /** Distinct per instance: both filters must run on the same request. */
        @Override
        protected String getAlreadyFilteredAttributeName() {
            return "rate-limit-" + System.identityHashCode(this);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (props.enabled() && !EXEMPT.matches(request)) {
                OptionalLong wait = check(request);
                if (wait.isPresent()) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(wait.getAsLong()));
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded\"}}");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
