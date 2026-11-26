package com.issuermatch.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stop at startup, with a clear message, when a secret is missing, instead of failing on the first request that
 * needs it. The database and token settings have no defaults, so Spring already fails fast for those.
 * Only the variable NAME is ever mentioned, never a value.
 */
@Component
class RequiredSecrets {

    RequiredSecrets(@Value("${spring.ai.google.genai.api-key:}") String geminiKey) {
        require("GEMINI_API_KEY", geminiKey);
    }

    static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name + " (see backend/.env.example)");
        }
    }
}
