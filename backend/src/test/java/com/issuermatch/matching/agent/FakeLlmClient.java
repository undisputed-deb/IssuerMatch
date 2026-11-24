package com.issuermatch.matching.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** Replays scripted behaviour in order and remembers what it was asked. Tests only. */
public class FakeLlmClient implements LlmClient {

    /** One scripted model turn. It may call tools (like the real model would) and then returns text. */
    @FunctionalInterface
    public interface Turn {
        LlmResult respond(String systemPrompt, String userPrompt, List<ToolCallback> tools);
    }

    public record Call(String systemPrompt, String userPrompt, List<ToolCallback> tools) {}

    private final Deque<Turn> turns = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public FakeLlmClient(String... answers) {
        script(answers);
    }

    /** Replace the script with plain text answers. */
    public synchronized FakeLlmClient script(String... answers) {
        turns.clear();
        calls.clear();
        for (String a : answers) turns.add((s, u, t) -> new LlmResult(a, 10, 5));
        return this;
    }

    /** Replace the script with arbitrary turns. */
    public synchronized FakeLlmClient scriptTurns(Turn... newTurns) {
        turns.clear();
        calls.clear();
        turns.addAll(List.of(newTurns));
        return this;
    }

    @Override
    public synchronized LlmResult complete(String systemPrompt, String userPrompt, List<ToolCallback> tools) {
        calls.add(new Call(systemPrompt, userPrompt, tools));
        Turn turn = turns.poll();
        if (turn == null) throw new IllegalStateException("FakeLlmClient: no scripted turn left");
        return turn.respond(systemPrompt, userPrompt, tools);
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Call a tool by name the way the model would. Returns the tool's JSON output. */
    public static String callTool(List<ToolCallback> tools, String name, String jsonInput) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool not offered to the model: " + name))
                .call(jsonInput);
    }
}
