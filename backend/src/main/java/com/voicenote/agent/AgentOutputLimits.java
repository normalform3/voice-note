package com.voicenote.agent;

import java.nio.charset.StandardCharsets;

public final class AgentOutputLimits {
    private AgentOutputLimits() { }
    public static int utf8Bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    public static String truncateUtf8(String value, int maximumBytes) {
        if (value == null || maximumBytes <= 0) return "";
        if (utf8Bytes(value) <= maximumBytes) return value;
        int bytes = 0; int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int nextBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + nextBytes > maximumBytes) break;
            bytes += nextBytes; end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }
}
