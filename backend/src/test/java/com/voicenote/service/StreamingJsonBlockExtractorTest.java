package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingJsonBlockExtractorTest {
    @Test
    void extractsOnlyCompleteBlocksAcrossEveryByteBoundary() {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> blocks = new ArrayList<>();
        String json = """
                {"resultSchemaVersion":3,"blocks":[
                  {"type":"SUMMARY","statements":[{"text":"中文\\\"摘要","evidenceRefs":["src_1"]}]},
                  {"type":"FINDINGS","items":[{"title":"风险","statements":[{"text":"需要跟进","evidenceRefs":["src_2"]}]}]}
                ],"limitations":[]}
                """;

        try (StreamingJsonBlockExtractor extractor = new StreamingJsonBlockExtractor(mapper, blocks::add)) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            for (byte value : bytes) extractor.feed(new byte[]{value});
        }

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).path("statements").path(0).path("text").asText()).isEqualTo("中文\"摘要");
        assertThat(blocks.get(1).path("items").path(0).path("title").asText()).isEqualTo("风险");
    }

    @Test
    void neverPublishesAnIncompleteTrailingObject() {
        List<JsonNode> blocks = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (StreamingJsonBlockExtractor extractor = new StreamingJsonBlockExtractor(mapper, blocks::add)) {
            extractor.feed("{\"blocks\":[{\"type\":\"SUMMARY\"");
        }
        assertThat(blocks).isEmpty();
    }

    @Test
    void ignoresBlocksWithoutTheExpectedTopLevelSchemaVersion() {
        List<JsonNode> blocks = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        try (StreamingJsonBlockExtractor extractor = new StreamingJsonBlockExtractor(mapper, blocks::add)) {
            extractor.feed("{\"resultSchemaVersion\":2,\"blocks\":[{\"type\":\"SUMMARY\"}]}");
            extractor.feed("{\"wrapper\":{\"blocks\":[{\"type\":\"SUMMARY\"}]},\"resultSchemaVersion\":3}");
        }
        assertThat(blocks).isEmpty();
    }
}
