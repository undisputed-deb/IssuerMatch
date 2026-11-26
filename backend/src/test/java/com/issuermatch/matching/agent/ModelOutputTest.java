package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ModelOutputTest {

    final JsonMapper mapper = JsonMapper.builder().build();
    final UUID inv = UUID.randomUUID();

    final UUID rival = UUID.randomUUID();

    static String hit(UUID id, String name) {
        return "{\"investorId\":\"" + id + "\",\"name\":\"" + name + "\"}";
    }

    static String scored(UUID id, String disqualifiers) {
        return "{\"investorId\":\"" + id + "\",\"total\":90,\"breakdown\":{\"disqualifiers\":" + disqualifiers + "}}";
    }

    List<RunToolbox.Call> goodCalls() {
        return List.of(
                new RunToolbox.Call("searchInvestorsByMandate", "{}", "[" + hit(inv, "Alpha Capital") + "," + hit(rival, "Rival Partners") + "]", false, 1),
                new RunToolbox.Call("scoreFit", "{}", "[" + scored(inv, "[]") + "," + scored(rival, "[]") + "]", false, 1));
    }

    String answer(String investorId, String explanation, String cited) {
        return Answers.rankings(Answers.entry(investorId, explanation, cited));
    }

    List<ModelOutput.Entry> parse(String text, List<RunToolbox.Call> calls) {
        return ModelOutput.parse(mapper, text, calls);
    }

    @Test
    void acceptsAWellFormedAnswerGroundedInToolCalls() {
        var entries = parse(answer(inv.toString(), "Good sector fit.", "[\"scoreFit\",\"searchInvestorsByMandate\"]"), goodCalls());
        assertThat(entries).singleElement().satisfies(e -> {
            assertThat(e.investorId()).isEqualTo(inv);
            assertThat(e.citedTools()).containsExactly("scoreFit", "searchInvestorsByMandate");
        });
    }

    @Test
    void toleratesMarkdownFencesAndChatterAroundTheJson() {
        String json = answer(inv.toString(), "ok", "[\"scoreFit\"]");
        assertThat(parse("Here you go:\n```json\n" + json + "\n```", goodCalls())).hasSize(1);
    }

    @Test
    void rejectsGarbageAndEmptyAndWrongShape() {
        for (String bad : new String[] {"", "no json", "{", "[]", "{\"rankings\":[]}", "{\"rankings\":null}", "{\"other\":1}"}) {
            assertThatThrownBy(() -> parse(bad, goodCalls())).as(bad).isInstanceOf(ModelOutput.Invalid.class);
        }
        assertThatThrownBy(() -> parse(null, goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> parse("{\"rankings\":[{\"investorId\":\"" + inv + "\",\"explanation\":\"x\",\"citedTools\":[\"scoreFit\"],\"score\":99}]}", goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsAnInvestorThatNoSearchReturned() {
        String invented = UUID.randomUUID().toString();
        assertThatThrownBy(() -> parse(answer(invented, "x", "[\"scoreFit\"]"), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("not in the search results");
    }

    @Test
    void rejectsAnInvestorThatWasNeverScored() {
        List<RunToolbox.Call> noScore = List.of(goodCalls().get(0));
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[\"searchInvestorsByMandate\"]"), noScore))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("scoreFit");
    }

    @Test
    void ignoresFailedToolCallsWhenCheckingGrounding() {
        List<RunToolbox.Call> failedScore = List.of(goodCalls().get(0),
                new RunToolbox.Call("scoreFit", "{}", "{\"error\":\"x\"}", true, 1));
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[\"scoreFit\"]"), failedScore))
                .isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsMissingOrUnusedCitations() {
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[\"getInvestorHistory\"]"), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("getInvestorHistory");
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[\"totallyMadeUpTool\"]"), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsBlankOrHugeExplanationsAndBadIds() {
        assertThatThrownBy(() -> parse(answer(inv.toString(), "   ", "[\"scoreFit\"]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x".repeat(501), "[\"scoreFit\"]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        assertThatThrownBy(() -> parse(answer("not-a-uuid", "x", "[\"scoreFit\"]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsDuplicateInvestorsAndTooManyRankings() {
        String one = Answers.entry(inv, "x", "[\"scoreFit\"]");
        assertThatThrownBy(() -> parse("{\"rankings\":[" + one + "," + one + "]}", goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        String eleven = String.join(",", java.util.Collections.nCopies(11, one));
        assertThatThrownBy(() -> parse("{\"rankings\":[" + eleven + "]}", goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsExplanationsWithMarkupOrLinks() {
        for (String evil : new String[] {"<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "See http://evil.example/leak",
                "Click [here](https://evil.example)", "javascript:alert(1)", "![x](data:text/html;base64,AAAA)", "go to www.evil.com now",
                "nice </p><b>fit"}) {
            assertThatThrownBy(() -> parse(answer(inv.toString(), evil, "[\"scoreFit\"]"), goodCalls()))
                    .as(evil).isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("plain text");
        }
    }

    @Test
    void ordinaryComparisonsAndSymbolsAreStillPlainText() {
        assertThat(parse(answer(inv.toString(), "Ticket 1M < max 2M, fit > 90%. Sector & region match (see score).", "[\"scoreFit\"]"), goodCalls()))
                .hasSize(1);
    }

    // ---- deal brief, diligence questions, disqualification ----

    String custom(String brief, String questions) {
        return Answers.rankings("{\"investorId\":\"" + inv + "\",\"explanation\":\"x\",\"citedTools\":[\"scoreFit\"],"
                + "\"dealBrief\":" + brief + ",\"suggestedDiligenceQuestions\":" + questions + "}");
    }

    static final String THREE = "[\"a?\",\"b?\",\"c?\"]";

    @Test
    void requiresABriefAndExactlyThreeQuestions() {
        assertThat(parse(custom("\"Good fit.\"", THREE), goodCalls())).hasSize(1);
        for (String[] bad : new String[][] {{"\"\"", THREE}, {"null", THREE}, {"\"" + "x".repeat(401) + "\"", THREE},
                {"\"b\"", "[\"a?\",\"b?\"]"}, {"\"b\"", "[\"1?\",\"2?\",\"3?\",\"4?\"]"},
                {"\"b\"", "null"}, {"\"b\"", "[\"a?\",\"\",\"c?\"]"}, {"\"see https://evil.example\"", THREE}}) {
            assertThatThrownBy(() -> parse(custom(bad[0], bad[1]), goodCalls())).as(bad[0] + bad[1]).isInstanceOf(ModelOutput.Invalid.class);
        }
    }

    @Test
    void theDealBriefIsAtMostTwoSentences() {
        assertThat(parse(custom("\"Raising USD 2.5 million, e.g. for hiring. It fits your focus.\"", THREE), goodCalls())).hasSize(1);
        assertThatThrownBy(() -> parse(custom("\"One. Two. Three.\"", THREE), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("2 sentences");
    }

    @Test
    void questionsAreShort() {
        String atLimit = "word ".repeat(30).strip();
        assertThat(parse(custom("\"ok\"", "[\"" + atLimit + "\",\"b?\",\"c?\"]"), goodCalls())).hasSize(1);
        assertThatThrownBy(() -> parse(custom("\"ok\"", "[\"" + atLimit + " more\",\"b?\",\"c?\"]"), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("20 words");
    }

    @Test
    void questionsAimedBackAtTheInvestorAreRejected() {
        for (String inverted : new String[] {"How flexible is your investment thesis?", "Does this fit your mandate?",
                "What is your typical investment horizon?", "How diversified is your portfolio?", "What size is your fund?",
                "How would you help with our expansion?", "How do you support our growth?"}) {
            assertThatThrownBy(() -> parse(custom("\"ok\"", "[\"" + inverted + "\",\"b?\",\"c?\"]"), goodCalls()))
                    .as(inverted).isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("founder");
        }
        for (String fine : new String[] {"How concentrated is your loan portfolio?", "How long does your funding last?",
                "Which customers support your revenue?"}) {
            assertThat(parse(custom("\"ok\"", "[\"" + fine + "\",\"b?\",\"c?\"]"), goodCalls())).as(fine).hasSize(1);
        }
    }

    @Test
    void thePromptDirectsQuestionsAtTheFounderAndCapsTheOutput() {
        assertThat(MatchAgent.SYSTEM_PROMPT.replaceAll("\\s+", " "))
                .contains("asked BY the investor TO the issuer's founder")
                .contains("Never ask about the investor's own mandate")
                .contains("exactly 3 questions of at most 20 words")
                .contains("revenue durability", "customer concentration", "licensing or regulatory risk", "burn and runway",
                        "valuation assumptions")
                .contains("dealBrief is exactly 2 sentences")
                .contains("1-2 crisp sentences")
                .contains("Rank only the 5 best qualified investors");
    }

    @Test
    void textShownToOneInvestorMayNotNameAnother() {
        assertThatThrownBy(() -> parse(custom("\"Stronger than rival partners.\"", THREE), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("another investor");
        assertThatThrownBy(() -> parse(custom("\"ok\"", "[\"Is Rival Partners involved?\",\"b?\",\"c?\"]"), goodCalls()))
                .isInstanceOf(ModelOutput.Invalid.class);
        assertThat(parse(custom("\"Alpha Capital fits well.\"", THREE), goodCalls())).hasSize(1); // its own name is fine
    }

    @Test
    void aDisqualifiedInvestorCannotBeRanked() {
        List<RunToolbox.Call> calls = List.of(goodCalls().get(0),
                new RunToolbox.Call("scoreFit", "{}", "[" + scored(inv, "[\"excluded type\"]") + "]", false, 1));
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x", "[\"scoreFit\"]"), calls))
                .isInstanceOf(ModelOutput.Invalid.class).hasMessageContaining("disqualified");
    }

    @Test
    void aSemanticSearchHitIsAValidCandidate() {
        List<RunToolbox.Call> calls = List.of(
                new RunToolbox.Call("searchInvestorsByThesisSemantic", "{}", "[" + hit(inv, "Alpha Capital") + "]", false, 1),
                new RunToolbox.Call("scoreFit", "{}", "[" + scored(inv, "[]") + "]", false, 1));
        assertThat(parse(answer(inv.toString(), "x", "[\"searchInvestorsByThesisSemantic\"]"), calls)).hasSize(1);
    }
}
