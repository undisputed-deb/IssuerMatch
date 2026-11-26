package com.issuermatch.security;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Requests allowed per minute. "llm" limits apply to the endpoints that start a paid model call:
 * one per user, and one shared by everybody as a ceiling on spend. "signup" limits public account creation,
 * per address and overall. "invite" limits creating team invitations per user and reading their public preview per
 * address.
 */
@Validated
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("300") @Min(1) long ipPerMinute,
        @DefaultValue("120") @Min(1) long userPerMinute,
        @DefaultValue("10") @Min(1) long llmPerMinute,
        @DefaultValue("60") @Min(1) long llmGlobalPerMinute,
        @DefaultValue("5") @Min(1) long signupPerMinute,
        @DefaultValue("30") @Min(1) long signupGlobalPerMinute,
        @DefaultValue("10") @Min(1) long inviteCreatePerMinute,
        @DefaultValue("30") @Min(1) long invitePreviewPerMinute) {}
