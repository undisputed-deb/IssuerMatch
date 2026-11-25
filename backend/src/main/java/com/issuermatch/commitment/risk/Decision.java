package com.issuermatch.commitment.risk;

/** One rule's answer, always with a human-readable reason. */
public record Decision(Verdict verdict, String reason) {

    public static Decision pass(String reason) {
        return new Decision(Verdict.PASS, reason);
    }

    public static Decision flag(String reason) {
        return new Decision(Verdict.FLAG, reason);
    }

    public static Decision block(String reason) {
        return new Decision(Verdict.BLOCK, reason);
    }
}
