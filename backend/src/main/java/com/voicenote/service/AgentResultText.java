package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

final class AgentResultText {
    private AgentResultText() { }

    static String extract(ObjectMapper mapper, String document) {
        if (document == null || document.isBlank()) return "";
        try { return extract(mapper.readTree(document)); }
        catch (Exception exception) { return document; }
    }

    private static String extract(JsonNode result) {
        if (result.path("answer").isTextual()) return result.path("answer").asText();
        List<String> output = new ArrayList<>();
        if (result.path("blocks").isArray()) result.path("blocks").forEach(block -> appendBlock(output, block));
        return output.isEmpty() ? result.toString() : String.join("\n", output);
    }

    private static void appendBlock(List<String> output, JsonNode block) {
        appendStatements(output, block.path("statements"));
        appendText(output, block.path("content"));
        if (block.path("items").isArray()) block.path("items").forEach(item -> {
            appendFirstText(output, item, "title", "question", "dimension", "label");
            appendStatements(output, item.path("statements"));
            appendFirstText(output, item, "content", "answer");
        });
        if (block.path("rows").isArray()) block.path("rows").forEach(row -> {
            appendFirstText(output, row, "label", "title");
            if (row.path("cells").isArray()) row.path("cells").forEach(cell -> appendText(output, cell.path("text")));
            if (row.path("values").isArray()) row.path("values").forEach(value -> appendText(output, value));
        });
    }

    private static void appendStatements(List<String> output, JsonNode statements) {
        if (!statements.isArray()) return;
        List<String> sentenceText = new ArrayList<>();
        statements.forEach(statement -> {
            if (statement.path("text").isTextual() && !statement.path("text").asText().isBlank()) {
                sentenceText.add(statement.path("text").asText().trim());
            }
        });
        if (!sentenceText.isEmpty()) output.add(String.join(" ", sentenceText));
    }

    private static void appendFirstText(List<String> output, JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.path(field).isTextual() && !node.path(field).asText().isBlank()) {
                output.add(node.path(field).asText().trim());
                return;
            }
        }
    }

    private static void appendText(List<String> output, JsonNode value) {
        if (value.isTextual() && !value.asText().isBlank()) output.add(value.asText().trim());
    }
}
