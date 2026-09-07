package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DashscopeHotwordVocabularyProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsAParaformerVocabularyWithFixedWeightsAndLanguages() throws IOException {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/audio/asr/customization", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"output\":{\"vocabulary_id\":\"vocab-123\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1");
        DashscopeHotwordVocabularyProvider provider = new DashscopeHotwordVocabularyProvider(properties, mapper);

        String id = provider.create("vn12345678", "paraformer-v2", List.of(
                new HotwordVocabularyProvider.Entry("VoiceNote", 4, "en"),
                new HotwordVocabularyProvider.Entry("语音实验室", 4, "zh")));

        assertThat(id).isEqualTo("vocab-123");
        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo("speech-biasing");
        assertThat(body.path("input").path("action").asText()).isEqualTo("create_vocabulary");
        assertThat(body.path("input").path("target_model").asText()).isEqualTo("paraformer-v2");
        assertThat(body.path("input").path("prefix").asText()).isEqualTo("vn12345678");
        assertThat(body.path("input").path("vocabulary")).hasSize(2);
        assertThat(body.path("input").path("vocabulary").get(0).path("weight").asInt()).isEqualTo(4);
        assertThat(body.path("input").path("vocabulary").get(0).path("lang").asText()).isEqualTo("en");
        assertThat(body.path("input").path("vocabulary").get(1).path("lang").asText()).isEqualTo("zh");
    }

    @Test
    void identifiesProviderCapacityExhaustionWithoutExposingTheRawResponse() {
        ProviderException exception = DashscopeHotwordVocabularyProvider.classifyHttp(400,
                "{\"code\":\"Throttling.AllocationQuota\",\"message\":\"Free allocated quota exceeded.\"}");

        assertThat(exception.getCode()).isEqualTo("HOTWORD_PROVIDER_CAPACITY_EXHAUSTED");
        assertThat(exception.getMessage()).contains("词表额度已满").doesNotContain("Throttling.AllocationQuota");
    }
}
