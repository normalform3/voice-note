package com.voicenote.service;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {
    @Test
    void canonicalJsonHashIgnoresMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>(); first.put("audioBlobId", "a"); first.put("mode", "meeting");
        Map<String, Object> second = new LinkedHashMap<>(); second.put("mode", "meeting"); second.put("audioBlobId", "a");
        assertThat(Hashing.canonicalJsonHash(first)).isEqualTo(Hashing.canonicalJsonHash(second));
    }
}
