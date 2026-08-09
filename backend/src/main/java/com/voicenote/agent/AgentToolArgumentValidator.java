package com.voicenote.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;

/** Small, deterministic validator for the JSON Schema subset used by Agent tools. */
public final class AgentToolArgumentValidator {
    private AgentToolArgumentValidator() { }

    public static void validate(JsonNode schema, JsonNode value) {
        validate(schema, value, "$", true);
    }

    private static void validate(JsonNode schema, JsonNode value, String path, boolean root) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) return;
        String type = schema.path("type").asText();
        if ((root || "object".equals(type)) && !value.isObject()) fail(path, "must be an object");
        if ("array".equals(type) && !value.isArray()) fail(path, "must be an array");
        if ("string".equals(type) && !value.isTextual()) fail(path, "must be a string");
        if ("integer".equals(type) && !value.isIntegralNumber()) fail(path, "must be an integer");
        if ("number".equals(type) && !value.isNumber()) fail(path, "must be a number");
        if ("boolean".equals(type) && !value.isBoolean()) fail(path, "must be a boolean");

        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                String name = required.asText();
                if (!value.has(name) || value.get(name).isNull()) fail(path + "." + name, "is required");
            }
            JsonNode properties = schema.path("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode childSchema = properties.get(field.getKey());
                if (childSchema != null) validate(childSchema, field.getValue(), path + "." + field.getKey(), false);
                else if (schema.has("additionalProperties") && !schema.path("additionalProperties").asBoolean(true)) fail(path + "." + field.getKey(), "is not allowed");
            }
        }
        if (value.isArray()) {
            int minItems = schema.path("minItems").asInt(0);
            int maxItems = schema.path("maxItems").asInt(Integer.MAX_VALUE);
            if (value.size() < minItems || value.size() > maxItems) fail(path, "contains an invalid number of items");
            for (int index = 0; index < value.size(); index++) validate(schema.path("items"), value.get(index), path + "[" + index + "]", false);
        }
        if (value.isTextual()) {
            int minLength = schema.path("minLength").asInt(0);
            int maxLength = schema.path("maxLength").asInt(Integer.MAX_VALUE);
            if (value.asText().length() < minLength || value.asText().length() > maxLength) fail(path, "has an invalid length");
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.path("minimum").asDouble()) fail(path, "is below minimum");
            if (schema.has("maximum") && value.asDouble() > schema.path("maximum").asDouble()) fail(path, "is above maximum");
        }
        if (schema.path("enum").isArray()) {
            boolean accepted = false;
            for (JsonNode candidate : schema.path("enum")) if (candidate.equals(value)) accepted = true;
            if (!accepted) fail(path, "is not one of the allowed values");
        }
    }

    private static void fail(String path, String reason) {
        throw new IllegalArgumentException("Invalid tool arguments: " + path + " " + reason);
    }
}
