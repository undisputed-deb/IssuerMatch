package com.issuermatch.matching.agent;

/** Raw model output plus usage, so every run can log tokens and latency. */
public record LlmResult(String text, int tokensIn, int tokensOut) {}
