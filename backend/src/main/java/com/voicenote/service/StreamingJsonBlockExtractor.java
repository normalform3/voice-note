package com.voicenote.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Extracts complete objects from the top-level blocks array without accepting partial JSON. */
final class StreamingJsonBlockExtractor implements AutoCloseable {
    private final ObjectMapper mapper;
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;
    private final Consumer<JsonNode> consumer;
    private String rootField;
    private boolean schemaVersionThree;
    private boolean inBlocks;
    private int depth;
    private TokenBuffer block;
    private int blockDepth;
    private boolean failed;

    StreamingJsonBlockExtractor(ObjectMapper mapper, Consumer<JsonNode> consumer) {
        try {
            this.mapper = mapper; this.consumer = consumer;
            this.parser = new JsonFactory().createNonBlockingByteArrayParser();
            this.feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        } catch (IOException exception) { throw new IllegalStateException("Cannot create streaming JSON parser", exception); }
    }

    void feed(String value) {
        if (failed || value == null || value.isEmpty()) return;
        feed(value.getBytes(StandardCharsets.UTF_8));
    }

    void feed(byte[] bytes) {
        if (failed || bytes.length == 0) return;
        try {
            if (!feeder.needMoreInput()) drain();
            feeder.feedInput(bytes, 0, bytes.length);
            drain();
        } catch (Exception exception) {
            failed = true;
        }
    }

    private void drain() throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.NOT_AVAILABLE && token != null) accept(token);
    }

    private void accept(JsonToken token) throws IOException {
        if (block != null) {
            block.copyCurrentEvent(parser);
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) { blockDepth++; depth++; }
            else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) { blockDepth--; depth--; }
            if (blockDepth == 0) {
                try (JsonParser buffered = block.asParser(mapper)) {
                    consumer.accept(mapper.readTree(buffered));
                } finally {
                    block.close(); block = null;
                }
            }
            return;
        }
        if (token == JsonToken.FIELD_NAME) {
            rootField = depth == 1 ? parser.currentName() : null;
            return;
        }
        if (depth == 1 && "resultSchemaVersion".equals(rootField) && token == JsonToken.VALUE_NUMBER_INT) {
            schemaVersionThree = parser.getIntValue() == 3; rootField = null; return;
        }
        if (token == JsonToken.START_OBJECT) {
            if (inBlocks && depth == 2) {
                block = new TokenBuffer(mapper, false); block.copyCurrentEvent(parser); blockDepth = 1;
            }
            depth++; rootField = null; return;
        }
        if (token == JsonToken.START_ARRAY) {
            if (depth == 1 && "blocks".equals(rootField) && schemaVersionThree) inBlocks = true;
            depth++; rootField = null; return;
        }
        if (token == JsonToken.END_ARRAY) {
            depth--; if (inBlocks && depth == 1) inBlocks = false; rootField = null; return;
        }
        if (token == JsonToken.END_OBJECT) { depth--; rootField = null; return; }
        rootField = null;
    }

    @Override public void close() {
        try {
            if (!failed) { feeder.endOfInput(); drain(); }
            parser.close();
            if (block != null) block.close();
        } catch (IOException ignored) { }
    }
}
