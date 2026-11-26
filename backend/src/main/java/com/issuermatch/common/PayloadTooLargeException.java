package com.issuermatch.common;

import java.io.IOException;

/** Thrown while reading a body that grew past the limit. Becomes a 413. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException() {
        super("Request body is too large");
    }
}
