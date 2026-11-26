package com.issuermatch.fundingneed;

import com.issuermatch.common.Instrument;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.node.ObjectNode;

/**
 * Terms that depend on the instrument. A sealed family with a "type" discriminator. The reader accepts exactly the
 * three names below: no class names are ever taken from the input, so there is no polymorphic-deserialisation gadget
 * to abuse.
 */
@JsonDeserialize(using = InstrumentTerms.Reader.class)
public sealed interface InstrumentTerms permits EquityTerms, DebtTerms, ConvertibleTerms {

    /** EQUITY, DEBT or CONVERTIBLE. Serialised as the "type" property. */
    String getType();

    /** Whether these terms make sense for a need raising through this instrument. Mezzanine is a form of debt. */
    default boolean fits(Instrument instrument) {
        return switch (this) {
            case EquityTerms e -> instrument == Instrument.EQUITY;
            case DebtTerms d -> instrument == Instrument.DEBT || instrument == Instrument.MEZZANINE;
            case ConvertibleTerms c -> instrument == Instrument.CONVERTIBLE;
        };
    }

    final class Reader extends ValueDeserializer<InstrumentTerms> {
        @Override
        public InstrumentTerms deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode node = context.readTree(parser);
            if (!(node instanceof ObjectNode object)) {
                return context.reportInputMismatch(InstrumentTerms.class, "instrumentTerms must be an object");
            }
            String type = object.path("type").asString("");
            ObjectNode body = object.deepCopy();
            body.remove("type");
            // each concrete record clears this reader (see their annotation), so reading them does not recurse
            return switch (type) {
                case "EQUITY" -> context.readTreeAsValue(body, EquityTerms.class);
                case "DEBT" -> context.readTreeAsValue(body, DebtTerms.class);
                case "CONVERTIBLE" -> context.readTreeAsValue(body, ConvertibleTerms.class);
                default -> context.reportInputMismatch(InstrumentTerms.class, "instrumentTerms.type must be EQUITY, DEBT or CONVERTIBLE");
            };
        }
    }
}
