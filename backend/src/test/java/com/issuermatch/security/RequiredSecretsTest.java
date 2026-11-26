package com.issuermatch.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequiredSecretsTest {

    @Test
    void aMissingSecretStopsStartupNamingOnlyTheVariable() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> RequiredSecrets.require("GEMINI_API_KEY", blank))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("GEMINI_API_KEY");
        }
        assertThatCode(() -> RequiredSecrets.require("GEMINI_API_KEY", "something")).doesNotThrowAnyException();
    }
}
