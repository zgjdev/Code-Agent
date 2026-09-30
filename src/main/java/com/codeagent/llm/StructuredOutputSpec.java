package com.codeagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.regex.Pattern;

/** Provider-independent structured-output contract. */
public record StructuredOutputSpec(String name, JsonNode schema, boolean strict) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public StructuredOutputSpec {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Structured output name must match " + NAME.pattern());
        }
        Objects.requireNonNull(schema, "schema");
        schema = schema.deepCopy();
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }
}
