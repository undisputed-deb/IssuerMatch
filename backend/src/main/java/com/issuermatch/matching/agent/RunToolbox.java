package com.issuermatch.matching.agent;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * One per agent run. Wraps the allowed tools so that every call is (a) counted against a hard budget
 * and (b) recorded with its input and output. The record is stored with the run for explainability.
 */
public final class RunToolbox {

    public record Call(String tool, String input, String output, boolean error, long ms) {}

    static final String BUDGET_MESSAGE =
            "{\"error\":\"Tool call budget exhausted. Stop calling tools and give your final answer now.\"}";

    /** Per-run permission check on tool inputs. Returns a reason to refuse, or empty to allow. */
    @FunctionalInterface
    interface Policy {
        java.util.Optional<String> deny(String tool, String input);

        Policy ALLOW_ALL = (tool, input) -> java.util.Optional.empty();
    }

    private final int maxCalls;
    private final Policy policy;
    private final List<Call> calls = new ArrayList<>();
    private final List<ToolCallback> wrapped;
    private int executed;

    RunToolbox(List<ToolCallback> allowed, int maxCalls, Policy policy) {
        this.maxCalls = maxCalls;
        this.policy = policy;
        this.wrapped = allowed.stream().<ToolCallback>map(Recording::new).toList();
    }

    List<ToolCallback> callbacks() {
        return wrapped;
    }

    synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    private synchronized boolean tryAcquire() {
        if (executed >= maxCalls) {
            return false;
        }
        executed++;
        return true;
    }

    private synchronized void record(Call call) {
        calls.add(call);
    }

    private final class Recording implements ToolCallback {
        private final ToolCallback delegate;

        Recording(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String input) {
            String name = delegate.getToolDefinition().name();
            if (!tryAcquire()) {
                record(new Call(name, input, BUDGET_MESSAGE, true, 0));
                return BUDGET_MESSAGE;
            }
            java.util.Optional<String> denied = policy.deny(name, input);
            if (denied.isPresent()) {
                String out = "{\"error\":" + quote("Not permitted: " + denied.get()) + "}";
                record(new Call(name, input, out, true, 0));
                return out;
            }
            long start = System.nanoTime();
            try {
                String out = delegate.call(input);
                record(new Call(name, input, out, false, (System.nanoTime() - start) / 1_000_000));
                return out;
            } catch (RuntimeException e) {
                // Tool errors go back to the model as text (it can adapt) and into the record.
                String out = "{\"error\":" + quote(rootMessage(e)) + "}";
                record(new Call(name, input, out, true, (System.nanoTime() - start) / 1_000_000));
                return out;
            }
        }

        @Override
        public String call(String input, ToolContext context) {
            return call(input);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? "tool failed" : t.getMessage();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
