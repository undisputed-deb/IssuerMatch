package com.issuermatch.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Extends Spring's base handler so framework errors (404 route, 405, bad JSON, ...) also use our format.
 * Clients only get generic messages. Details go to the server log.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> apiException(ApiException e) {
        return body(e.status(), e.status().name(), e.getMessage());
    }

    /** Unique / foreign-key / check violations. The SQL detail stays in the log, not in the response. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict(DataIntegrityViolationException e) {
        // The database's message can quote the offending row (names, funding text). Log only what identifies the rule.
        log.warn("Data integrity violation: {}", describe(e.getMostSpecificCause()));
        return body(HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with existing data");
    }

    /** @PreAuthorize denials surface as exceptions inside the controller layer. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauthorized(AuthenticationException e) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }

    /** ?sort=<unknown field>. Client mistake, not a server error. */
    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<ErrorResponse> badSort(PropertyReferenceException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid sort property");
    }

    /** SQLState class 08 = connection failure; 57P01-57P03 = the server is shutting down or not accepting connections. */
    private static boolean isLostConnection(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("57P0"))) {
                    return true;
                }
                // the connection pool's own answer after it has thrown a broken connection away (seen in chaos.sh)
                if (state == null && "Connection is closed".equals(sql.getMessage())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern CONSTRAINT = java.util.regex.Pattern.compile("constraint \"(\\w+)\"");

    private static String describe(Throwable cause) {
        String state = cause instanceof java.sql.SQLException sql ? sql.getSQLState() : "?";
        var name = CONSTRAINT.matcher(String.valueOf(cause.getMessage()));
        return "SQLState=" + state + " constraint=" + (name.find() ? name.group(1) : "?");
    }

    /** The database cannot be reached: say so (503, retry shortly) instead of a generic 500. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ErrorResponse> databaseUnavailable(Exception e) {
        return unavailable(e);
    }

    private ResponseEntity<ErrorResponse> unavailable(Exception e) {
        log.error("Database unavailable: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ErrorResponse.of("SERVICE_UNAVAILABLE", "Service temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        if (isLostConnection(e)) {
            return unavailable(e); // a connection that dies mid-request arrives wrapped in all sorts of exceptions
        }
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "Unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return objectBody(status, "VALIDATION_ERROR", message);
    }

    /** A chunked body that passed the size limit fails while being parsed: report it as 413, not as bad JSON. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException) {
                return objectBody(HttpStatus.valueOf(413), "PAYLOAD_TOO_LARGE", cause.getMessage());
            }
        }
        return handleExceptionInternal(e, null, headers, status, request);
    }

    /** Every other Spring MVC exception lands here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus http = HttpStatus.valueOf(status.value());
        return objectBody(status, http.name(), http.getReasonPhrase());
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }

    private static ResponseEntity<Object> objectBody(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
