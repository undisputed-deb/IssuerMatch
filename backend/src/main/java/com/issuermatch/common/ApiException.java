package com.issuermatch.common;

import org.springframework.http.HttpStatus;

/** Business error with a client-safe message. Subclass per HTTP status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
