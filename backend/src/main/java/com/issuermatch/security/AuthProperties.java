package com.issuermatch.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All three must be set. Startup fails otherwise, so the API can never run with token checking half configured. */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(@NotBlank String jwksUri, @NotBlank String issuer, @NotBlank String audience) {}
