package com.issuermatch.security;

/** Application roles. They come only from the JWT's app_metadata, which users cannot edit. */
public enum Role {
    ISSUER, ANALYST, ADMIN
}
