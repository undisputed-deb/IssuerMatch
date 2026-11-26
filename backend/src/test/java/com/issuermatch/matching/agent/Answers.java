package com.issuermatch.matching.agent;

/** Builds the model's JSON answer the way a well-behaved model would. Tests only. */
public final class Answers {

    private Answers() {}

    public static String entry(Object investorId, String explanation, String citedToolsJson) {
        return "{\"investorId\":\"" + investorId + "\",\"explanation\":\"" + explanation + "\",\"citedTools\":" + citedToolsJson
                + ",\"dealBrief\":\"This deal matches your focus.\",\"suggestedDiligenceQuestions\":"
                + "[\"What is current revenue?\",\"How long is the runway?\",\"What changes with this capital?\"]}";
    }

    public static String rankings(String... entries) {
        return "{\"rankings\":[" + String.join(",", entries) + "]}";
    }
}
