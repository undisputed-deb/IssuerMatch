package com.issuermatch.matching.agent;

/** The agent run failed in a way we handle safely. The message is safe to store and show. */
public class AgentFailure extends RuntimeException {

    public AgentFailure(String message) {
        super(message);
    }

    public AgentFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
