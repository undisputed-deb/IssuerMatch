package com.issuermatch.common;

/** The one error shape every endpoint returns: {"success":false,"error":{"code":"...","message":"..."}}. */
public record ErrorResponse(boolean success, Body error) {

    public record Body(String code, String message) {}

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(false, new Body(code, message));
    }
}
