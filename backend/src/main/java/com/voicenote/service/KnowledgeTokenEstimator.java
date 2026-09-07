package com.voicenote.service;

import org.springframework.stereotype.Component;

/**
 * Conservative, dependency-free token estimator used only to plan chunk boundaries.
 * The embedding provider's reported usage remains authoritative after indexing.
 */
@Component
public class KnowledgeTokenEstimator {
    public int estimate(String value) {
        if (value == null || value.isBlank()) return 0;
        int tokens = 0;
        int latinRun = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isAsciiWord(codePoint)) {
                latinRun++;
                continue;
            }
            tokens += latinTokens(latinRun);
            latinRun = 0;
            if (Character.isWhitespace(codePoint)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                tokens++;
            } else {
                // Count punctuation and non-Latin scripts conservatively as one token.
                tokens++;
            }
        }
        tokens += latinTokens(latinRun);
        return Math.max(1, (int) Math.ceil(tokens * 1.10) + 2);
    }

    private static boolean isAsciiWord(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-');
    }

    private static int latinTokens(int characters) {
        return characters == 0 ? 0 : Math.max(1, (characters + 3) / 4);
    }
}
