package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

final class AgentSpokenTextFormatter {
    private static final Set<String> SPOKEN_DETAIL_BLOCKS = Set.of(
            "FINDINGS", "DECISIONS", "ACTION_ITEMS", "QA_REVIEW", "ASSESSMENT_MATRIX");

    private AgentSpokenTextFormatter() { }

    static String format(JsonNode block) {
        String type = block.path("type").asText();
        StringBuilder text = new StringBuilder();
        if ("SUMMARY".equals(type)) appendStatements(text, block.path("statements"), Integer.MAX_VALUE);
        else if (SPOKEN_DETAIL_BLOCKS.contains(type)) {
            int items = 0;
            for (JsonNode item : block.path("items")) {
                if (items++ >= 3) break;
                appendStatements(text, item.path("statements"), 1);
            }
        }
        String value = text.toString().replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private static void appendStatements(StringBuilder output, JsonNode statements, int limit) {
        if (!statements.isArray()) return;
        int count = 0;
        for (JsonNode statement : statements) {
            if (count++ >= limit) break;
            String value = statement.path("text").asText("").trim();
            if (value.isEmpty()) continue;
            if (!output.isEmpty()) output.append('。');
            output.append(value);
        }
    }
}
