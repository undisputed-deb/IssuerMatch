package com.issuermatch.matching.agent;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Gemini through Spring AI. Spring AI runs the tool-calling loop; our tool wrappers cap and record it. */
@Component
class SpringAiLlmClient implements LlmClient {

    private final ChatClient chat;

    SpringAiLlmClient(ChatModel model) {
        this.chat = ChatClient.create(model);
    }

    @Override
    public LlmResult complete(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        ChatResponse response = chat.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .toolCallbacks(tools)
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new AgentFailure("Model returned no answer");
        }
        Usage usage = response.getMetadata().getUsage();
        int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return new LlmResult(response.getResult().getOutput().getText(), in, out);
    }
}
