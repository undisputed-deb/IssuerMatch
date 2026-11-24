package com.issuermatch.matching.agent;

import java.util.List;

/**
 * The only way the agent talks to a language model. Production uses Gemini (via Spring AI),
 * tests use a scripted fake, so no test ever calls a paid API.
 */
public interface LlmClient {

    /**
     * @param systemPrompt trusted instructions
     * @param userPrompt   contains untrusted funding-need text, already delimited by the caller
     * @param tools        objects with @Tool methods the model is allowed to call (an allowlist)
     */
    LlmResult complete(String systemPrompt, String userPrompt, List<Object> tools);
}
