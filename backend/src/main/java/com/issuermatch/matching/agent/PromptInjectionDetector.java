package com.issuermatch.matching.agent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Looks for instruction-like content in untrusted funding text and names what it found, so the human reviewer sees it.
 * It is a tripwire, not the defence: pattern matching can be evaded. The defences are structural (fenced input,
 * read-only allowlisted tools, need-bound scoring, validated output, human approval) and hold even when this misses.
 */
public final class PromptInjectionDetector {

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        rule("ignore-instructions", "(ignore|disregard|forget|override|bypass)\\b.{0,40}\\b(previous|prior|above|earlier|all|your|system|these)\\b.{0,40}\\b(instruction|rule|prompt|message|guideline)");
        rule("role-change", "\\b(you are now|from now on you|act as (a|an|the)\\b|pretend (to be|you are)|new persona|developer mode|jailbreak)");
        rule("prompt-probe", "\\b(system prompt|(reveal|show|print|repeat)\\b.{0,30}\\b(your|the)\\b.{0,20}(instructions|prompt))");
        rule("fake-delimiter", "</?\\s*(funding_need_description|system|assistant|instructions?|tool_call)\\b");
        rule("data-exfiltration", "\\b(list|dump|export|send|return|print)\\b.{0,30}\\b(all|every)\\b.{0,30}\\b(investor|mandate|record|user|api key|secret)");
        rule("tool-steering", "\\b(call|invoke|run|use)\\b.{0,20}\\b(the )?(tool|function)\\b|\\bscorefit\\s*\\(|\\bsearchinvestorsbymandate\\b");
        rule("score-steering", "\\b(rank|score|rate|place|put)\\b.{0,40}\\b(first|top|highest|100|number one)\\b");
    }

    private static void rule(String name, String regex) {
        RULES.put(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    private PromptInjectionDetector() {}

    public static List<String> detect(String... texts) {
        String joined = normalise(String.join("\n", texts));
        List<String> found = new ArrayList<>();
        RULES.forEach((name, pattern) -> {
            if (pattern.matcher(joined).find()) found.add(name);
        });
        return List.copyOf(found);
    }

    /** Undo cheap disguises: full-width letters, zero-width characters, odd spacing. */
    private static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200F\\u2028-\\u202F\\u2060\\uFEFF\\u00AD]", "")
                .replaceAll("\\s+", " ");
    }
}
