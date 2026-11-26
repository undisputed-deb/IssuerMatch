package com.issuermatch.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Every JSON string the application reads (request bodies, Kafka events, model output) must be storable text:
 * no NUL characters and no unpaired surrogates. PostgreSQL rejects both, so without this they surfaced as
 * database errors deep inside a request.
 */
@Configuration
class StrictStrings {

    @Bean
    JacksonModule strictStringModule() {
        SimpleModule module = new SimpleModule("strict-strings");
        module.addDeserializer(String.class, new Strict());
        return module;
    }

    static boolean isStorable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\0' || Character.isLowSurrogate(c)) {
                return false; // a low surrogate here has no high surrogate in front of it
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++; // valid pair
            }
        }
        return true;
    }

    private static final class Strict extends StdScalarDeserializer<String> {
        Strict() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            String value = StringDeserializer.instance.deserialize(parser, context);
            if (value != null && !isStorable(value)) {
                return context.reportInputMismatch(String.class, "Text contains characters that cannot be stored");
            }
            return value;
        }
    }
}
