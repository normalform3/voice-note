package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes credentials and private service addresses before observable Agent data is persisted. */
public final class AgentTraceSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern HEADER_SECRET = Pattern.compile("(?i)(authorization|x-api-key|api[-_ ]?key|access[-_ ]?token)(\\s*[:=]\\s*)[^\\s,;]+");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);

    private AgentTraceSanitizer() { }

    public static String sanitizeJson(ObjectMapper mapper, String document) {
        if (document == null || document.isBlank()) return document;
        try {
            JsonNode root = mapper.readTree(document);
            sanitizeNode(root);
            return mapper.writeValueAsString(root);
        } catch (Exception exception) {
            return sanitizeText(document);
        }
    }

    public static String sanitizeText(String value) {
        if (value == null) return null;
        String sanitized = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        sanitized = HEADER_SECRET.matcher(sanitized).replaceAll("$1$2" + REDACTED);
        Matcher matcher = URL.matcher(sanitized);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String candidate = matcher.group();
            matcher.appendReplacement(output, Matcher.quoteReplacement(isPrivateAddress(candidate) ? REDACTED : candidate));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static void sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
            iterator.forEachRemaining(fields::add);
            for (Map.Entry<String, JsonNode> field : fields) {
                if (isSensitiveKey(field.getKey())) object.set(field.getKey(), TextNode.valueOf(REDACTED));
                else if (field.getValue().isTextual()) object.set(field.getKey(), TextNode.valueOf(sanitizeText(field.getValue().asText())));
                else sanitizeNode(field.getValue());
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode value = array.get(index);
                if (value.isTextual()) array.set(index, TextNode.valueOf(sanitizeText(value.asText())));
                else sanitizeNode(value);
            }
        }
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase().replaceAll("[^a-z0-9]", "");
        return normalized.contains("authorization") || normalized.contains("apikey")
                || normalized.contains("accesstoken") || normalized.contains("refreshtoken")
                || normalized.contains("accesskey") || normalized.contains("secret")
                || normalized.contains("password") || normalized.equals("cookie") || normalized.equals("setcookie")
                || normalized.contains("workspaceid") || normalized.contains("tenantid");
    }

    private static boolean isPrivateAddress(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null) return true;
            String host = uri.getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase();
            if (normalized.equals("localhost") || normalized.endsWith(".local") || normalized.equals("::1")) return true;
            if (normalized.startsWith("10.") || normalized.startsWith("192.168.") || normalized.startsWith("127.")) return true;
            if (normalized.startsWith("172.")) {
                String[] parts = normalized.split("\\.");
                if (parts.length > 1) {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                }
            }
            return false;
        } catch (Exception ignored) { return false; }
    }
}
