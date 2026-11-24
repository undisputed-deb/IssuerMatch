package com.issuermatch.matching.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and checks the model's final JSON. The model is not trusted: every investor must have come from
 * a search in THIS run and must have been scored by the scoreFit tool, and every cited tool must really
 * have been called. A score is never read from the model's text.
 */
public final class ModelOutput {

    static final int MAX_RANKINGS = 5;
    static final int MAX_EXPLANATION = 1500;
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record Entry(UUID investorId, String explanation, List<String> citedTools) {}

    private record Envelope(List<Entry> rankings) {}

    /** The answer broke a rule. The message is fed back to the model on the single retry. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    private ModelOutput() {}

    static List<Entry> parse(JsonMapper mapper, String text, List<RunToolbox.Call> calls) {
        if (text == null || text.isBlank()) {
            throw new Invalid("the answer was empty");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new Invalid("the answer contained no JSON object");
        }
        Envelope envelope;
        try {
            // strict on purpose, whatever the app-wide mapper is configured to do
            envelope = mapper.readerFor(Envelope.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new Invalid("the JSON did not match the required shape");
        }
        List<Entry> rankings = envelope == null ? null : envelope.rankings();
        if (rankings == null || rankings.isEmpty()) {
            throw new Invalid("rankings must be a non-empty list");
        }
        if (rankings.size() > MAX_RANKINGS) {
            throw new Invalid("at most " + MAX_RANKINGS + " rankings are allowed");
        }
        validate(rankings, calls);
        return rankings;
    }

    private static void validate(List<Entry> rankings, List<RunToolbox.Call> calls) {
        Set<UUID> candidates = candidates(calls);
        Set<String> toolsUsed = calls.stream().filter(c -> !c.error()).map(RunToolbox.Call::tool)
                .collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        for (Entry e : rankings) {
            if (e == null || e.investorId() == null) {
                throw new Invalid("every ranking needs an investorId (a UUID)");
            }
            if (!seen.add(e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " is listed twice");
            }
            if (!candidates.contains(e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " is not in the search results");
            }
            if (!scored(calls, e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " was never scored with scoreFit");
            }
            if (e.explanation() == null || e.explanation().isBlank() || e.explanation().length() > MAX_EXPLANATION) {
                throw new Invalid("explanation must be 1-" + MAX_EXPLANATION + " characters");
            }
            if (e.citedTools() == null || e.citedTools().isEmpty()) {
                throw new Invalid("citedTools must list the tools you used for investor " + e.investorId());
            }
            for (String tool : e.citedTools()) {
                if (!toolsUsed.contains(tool)) {
                    throw new Invalid("citedTools names '" + tool + "' but that tool was not called successfully");
                }
            }
        }
    }

    /** Investor ids that appeared in successful searchInvestorsByMandate results of this run. */
    private static Set<UUID> candidates(List<RunToolbox.Call> calls) {
        Set<UUID> ids = new HashSet<>();
        for (RunToolbox.Call c : calls) {
            if (c.error() || !c.tool().equals("searchInvestorsByMandate")) continue;
            Matcher m = UUID_PATTERN.matcher(c.output());
            while (m.find()) ids.add(UUID.fromString(m.group()));
        }
        return ids;
    }

    private static boolean scored(List<RunToolbox.Call> calls, UUID investorId) {
        return calls.stream().anyMatch(c -> !c.error() && c.tool().equals("scoreFit")
                && c.input().contains(investorId.toString()));
    }
}
