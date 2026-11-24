package com.issuermatch.matching.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

    @Test
    void replaysAnswersInOrderAndRecordsCalls() {
        FakeLlmClient llm = new FakeLlmClient("first", "second");
        assertThat(llm.complete("sys", "user1", List.of()).text()).isEqualTo("first");
        assertThat(llm.complete("sys", "user2", List.of()).text()).isEqualTo("second");
        assertThat(llm.calls()).extracting(FakeLlmClient.Call::userPrompt).containsExactly("user1", "user2");
        assertThatThrownBy(() -> llm.complete("s", "u", List.of())).isInstanceOf(IllegalStateException.class);
    }
}
