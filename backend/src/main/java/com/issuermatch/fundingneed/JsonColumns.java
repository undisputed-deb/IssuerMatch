package com.issuermatch.fundingneed;

import jakarta.persistence.AttributeConverter;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JPA converters for the jsonb columns, using Jackson 3. They only ever read what this application wrote. */
final class JsonColumns {

    private JsonColumns() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final class UseOfFunds implements AttributeConverter<List<String>, String> {
        private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(List<String> value) {
            return JSON.writeValueAsString(value == null ? List.of() : value);
        }

        @Override
        public List<String> convertToEntityAttribute(String column) {
            return column == null ? List.of() : JSON.readValue(column, TYPE);
        }
    }

    static final class Constraints implements AttributeConverter<HardConstraints, String> {
        @Override
        public String convertToDatabaseColumn(HardConstraints value) {
            return JSON.writeValueAsString(value == null ? HardConstraints.NONE : value);
        }

        @Override
        public HardConstraints convertToEntityAttribute(String column) {
            return column == null ? HardConstraints.NONE : JSON.readValue(column, HardConstraints.class);
        }
    }

    static final class Terms implements AttributeConverter<InstrumentTerms, String> {
        @Override
        public String convertToDatabaseColumn(InstrumentTerms value) {
            return value == null ? null : JSON.writeValueAsString(value);
        }

        @Override
        public InstrumentTerms convertToEntityAttribute(String column) {
            return column == null ? null : JSON.readValue(column, InstrumentTerms.class);
        }
    }
}
