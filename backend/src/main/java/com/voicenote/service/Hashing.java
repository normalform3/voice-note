package com.voicenote.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hashing {
    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();
    private Hashing() { }
    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder(64);
            for (byte b : digest) output.append(String.format("%02x", b));
            return output.toString();
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    public static String canonicalJsonHash(Object value) {
        try { return sha256(CANONICAL_MAPPER.writeValueAsBytes(value)); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Cannot serialize idempotency request", exception); }
    }
}
