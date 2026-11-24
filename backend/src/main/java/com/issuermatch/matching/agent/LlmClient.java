package com.issuermatch.matching.agent;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * The only way the agent talks to a language model. Production uses Gemini (via Spring AI),
 * tests use a scripted fake, so no test ever calls a paid API.
 */
public interface LlmClient {

    /**
     * @param systemPrompt trusted instructions
     * @param userPrompt   contains untrusted funding-need text, already delimited by the caller
     * @param tools        the allowlist: the model can call these and nothing else
     */
    LlmResult complete(String systemPrompt, String userPrompt, List<ToolCallback> tools);
}
