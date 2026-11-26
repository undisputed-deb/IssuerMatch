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

    List<RunToolbox.Call> goodCalls() {
        return List.of(
                new RunToolbox.Call("searchInvestorsByMandate", "{}", "[{\"investorId\":\"" + inv + "\",\"name\":\"X\"}]", false, 1),
                new RunToolbox.Call("scoreFit", "{\"needId\":\"" + UUID.randomUUID() + "\",\"investorId\":\"" + inv + "\"}", "{\"total\":90}", false, 1));
    }

    String answer(String investorId, String explanation, String cited) {
        return "{\"rankings\":[{\"investorId\":\"" + investorId + "\",\"explanation\":\"" + explanation
                + "\",\"citedTools\":" + cited + "}]}";
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
                new RunToolbox.Call("scoreFit", "{\"investorId\":\"" + inv + "\"}", "{\"error\":\"x\"}", true, 1));
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
        assertThatThrownBy(() -> parse(answer(inv.toString(), "x".repeat(1501), "[\"scoreFit\"]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        assertThatThrownBy(() -> parse(answer("not-a-uuid", "x", "[\"scoreFit\"]"), goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
    }

    @Test
    void rejectsDuplicateInvestorsAndTooManyRankings() {
        String one = "{\"investorId\":\"" + inv + "\",\"explanation\":\"x\",\"citedTools\":[\"scoreFit\"]}";
        assertThatThrownBy(() -> parse("{\"rankings\":[" + one + "," + one + "]}", goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
        String six = String.join(",", java.util.Collections.nCopies(6, one));
        assertThatThrownBy(() -> parse("{\"rankings\":[" + six + "]}", goodCalls())).isInstanceOf(ModelOutput.Invalid.class);
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
}
