package com.issuermatch.messaging;

/** The message itself is bad. Retrying cannot help, so it goes straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
