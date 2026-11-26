package com.issuermatch.security;

import com.issuermatch.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Checks query parameters before they reach the data layer. Without this a crafted sort expression or an enormous
 * page number made the database layer throw, which surfaced as a 500.
 */
@Configuration
class QueryParameterGuard implements WebMvcConfigurer, HandlerInterceptor {

    static final long MAX_PAGE = 100_000;
    private static final Pattern SORT = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}(,[A-Za-z][A-Za-z0-9]{0,63}){0,4}");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        for (Map.Entry<String, String[]> p : request.getParameterMap().entrySet()) {
            for (String value : p.getValue()) {
                if (value.indexOf('\0') >= 0) {
                    throw bad("Query parameters must not contain NUL characters");
                }
                if (p.getKey().equals("sort") && !value.isEmpty() && !SORT.matcher(value).matches()) {
                    throw bad("sort must look like: field or field,asc or field,desc");
                }
                if (p.getKey().equals("page") && tooLarge(value)) {
                    throw bad("page must be at most " + MAX_PAGE);
                }
            }
        }
        return true;
    }

    private static boolean tooLarge(String value) {
        return value.matches("\\d+") && (value.length() > 9 || Long.parseLong(value) > MAX_PAGE);
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
