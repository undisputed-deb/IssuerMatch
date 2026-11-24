package com.issuermatch.matching.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Replays scripted answers in order and remembers what it was asked. Tests only. */
public class FakeLlmClient implements LlmClient {

    public record Call(String systemPrompt, String userPrompt, List<Object> tools) {}

    private final Deque<LlmResult> answers = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public FakeLlmClient(String... answers) {
        for (String a : answers) this.answers.add(new LlmResult(a, 10, 5));
    }

    @Override
    public LlmResult complete(String systemPrompt, String userPrompt, List<Object> tools) {
        calls.add(new Call(systemPrompt, userPrompt, tools));
        if (answers.isEmpty()) throw new IllegalStateException("FakeLlmClient: no scripted answer left");
        return answers.poll();
    }

    public List<Call> calls() {
        return calls;
    }
}
