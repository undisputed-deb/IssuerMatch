package com.issuermatch.matching.agent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and checks the model's final JSON. The model is not trusted: every investor must have come from a search in
 * THIS run and have been scored by scoreFit without disqualifiers, every cited tool must really have been called, and
 * text that one investor will read must not name another. A score is never read from the model's text.
 */
public final class ModelOutput {

    /** The prompt asks for 5 and MatchRunService keeps the best 5. Slightly more is trimmed there, not retried. */
    static final int MAX_RANKINGS = 10;
    /** One or two sentences, with slack so a wordy answer does not cost a second model call. */
    static final int MAX_EXPLANATION = 500;
    static final int MAX_BRIEF = 400;
    static final int MAX_BRIEF_SENTENCES = 2;
    static final int QUESTIONS = 3;
    static final int MAX_QUESTION = 200;
    /** The prompt asks for 20. The slack stops a 22-word question from costing a whole second model call. */
    static final int MAX_QUESTION_WORDS = 30;
    /** A sentence ends at . ! or ? followed by space and a capital or digit, so "2.5" and "e.g. this" do not count. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\d\"“])");
    /** Questions go to the founder. These phrases only make sense aimed back at the investor who is asking. */
    private static final Pattern AIMED_AT_INVESTOR = Pattern.compile(
            "\\b(your (\\w+ )?(mandate|thesis|investment (thesis|criteria|strategy|committee|horizon|philosophy))"
                    + "|your (portfolio|fund)|our expansion|support our)\\b", Pattern.CASE_INSENSITIVE);
    /** Markup, links and script URLs have no place in model text: they are how model output attacks a browser. */
    private static final Pattern ACTIVE_CONTENT = Pattern.compile(
            "<[a-zA-Z/!?]|https?://|\\]\\(|javascript:|data:text|vbscript:|www\\.[a-z0-9-]+\\.[a-z]", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SEARCH_TOOLS = Set.of("searchInvestorsByMandate", "searchInvestorsByThesisSemantic");

    public record Entry(UUID investorId, String explanation, List<String> citedTools, String dealBrief,
                        List<String> suggestedDiligenceQuestions) {}

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
        validate(mapper, rankings, calls);
        return rankings;
    }

    private static void validate(JsonMapper mapper, List<Entry> rankings, List<RunToolbox.Call> calls) {
        Map<UUID, String> candidates = candidates(mapper, calls);
        Set<UUID> scored = scoredAndQualified(mapper, calls);
        Set<String> toolsUsed = calls.stream().filter(c -> !c.error()).map(RunToolbox.Call::tool).collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        for (Entry e : rankings) {
            if (e == null || e.investorId() == null) {
                throw new Invalid("every ranking needs an investorId (a UUID)");
            }
            if (!seen.add(e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " is listed twice");
            }
            if (!candidates.containsKey(e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " is not in the search results");
            }
            if (!scored.contains(e.investorId())) {
                throw new Invalid("investor " + e.investorId() + " has no qualifying scoreFit result (never scored, or disqualified)");
            }
            text(e.explanation(), "explanation", MAX_EXPLANATION);
            text(e.dealBrief(), "dealBrief", MAX_BRIEF);
            if (SENTENCE_BREAK.split(e.dealBrief().strip()).length > MAX_BRIEF_SENTENCES) {
                throw new Invalid("dealBrief must be " + MAX_BRIEF_SENTENCES + " sentences");
            }
            List<String> questions = e.suggestedDiligenceQuestions();
            if (questions == null || questions.size() != QUESTIONS) {
                throw new Invalid("suggestedDiligenceQuestions must hold exactly " + QUESTIONS + " questions");
            }
            questions.forEach(ModelOutput::question);
            noOtherInvestors(e, candidates);
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

    private static void text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new Invalid(field + " must be 1-" + max + " characters");
        }
        if (ACTIVE_CONTENT.matcher(value).find()) {
            throw new Invalid(field + " must be plain text: no markup, links or scripts");
        }
    }

    private static void question(String q) {
        text(q, "each diligence question", MAX_QUESTION);
        if (q.strip().split("\\s+").length > MAX_QUESTION_WORDS) {
            throw new Invalid("each diligence question must be at most 20 words");
        }
        if (AIMED_AT_INVESTOR.matcher(q).find()) {
            throw new Invalid("diligence questions are asked by the investor to the issuer's founder, not to the investor");
        }
    }

    /** The brief and questions are shown to that investor: they must never reveal who else is being considered. */
    private static void noOtherInvestors(Entry e, Map<UUID, String> candidates) {
        String visible = (e.dealBrief() + " " + String.join(" ", e.suggestedDiligenceQuestions())).toLowerCase();
        String own = candidates.getOrDefault(e.investorId(), "").toLowerCase();
        for (Map.Entry<UUID, String> other : candidates.entrySet()) {
            String name = other.getValue().toLowerCase();
            if (!other.getKey().equals(e.investorId()) && !name.isBlank() && !name.equals(own) && visible.contains(name)) {
                throw new Invalid("dealBrief and questions for one investor must not mention another investor");
            }
        }
    }

    /** Investor id and name from every successful search in this run. */
    private static Map<UUID, String> candidates(JsonMapper mapper, List<RunToolbox.Call> calls) {
        Map<UUID, String> found = new LinkedHashMap<>();
        for (RunToolbox.Call c : calls) {
            if (c.error() || !SEARCH_TOOLS.contains(c.tool())) continue;
            for (JsonNode hit : readArray(mapper, c.output())) {
                uuid(hit.path("investorId")).ifPresent(id -> found.put(id, hit.path("name").asString("")));
            }
        }
        return found;
    }

    private static Set<UUID> scoredAndQualified(JsonMapper mapper, List<RunToolbox.Call> calls) {
        Set<UUID> ok = new HashSet<>();
        for (RunToolbox.Call c : calls) {
            if (c.error() || !c.tool().equals("scoreFit")) continue;
            for (JsonNode result : readArray(mapper, c.output())) {
                JsonNode disqualifiers = result.path("breakdown").path("disqualifiers");
                if (result.path("total").isNumber() && disqualifiers.isArray() && disqualifiers.isEmpty()) {
                    uuid(result.path("investorId")).ifPresent(ok::add);
                }
            }
        }
        return ok;
    }

    private static Iterable<JsonNode> readArray(JsonMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node.isArray() ? node.values() : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static java.util.Optional<UUID> uuid(JsonNode node) {
        try {
            return java.util.Optional.of(UUID.fromString(node.asString("")));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
