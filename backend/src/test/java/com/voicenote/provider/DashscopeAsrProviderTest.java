package com.voicenote.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DashscopeAsrProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void explainsWhenTheAccountHasExhaustedItsFreeTierQuota() {
        ProviderException exception = DashscopeAsrProvider.classifyHttp(403,
                "{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"Free quota exhausted\"}");

        assertThat(exception.getCode()).isEqualTo("DASHSCOPE_QUOTA_EXHAUSTED");
        assertThat(exception.getMessage()).contains("免费额度已耗尽");
    }

    @Test
    void resolvesNativeAsrEndpointsBelowTheApiVersionPath() {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory("https://example.invalid/api/v1");

        assertThat(factory.builder().path(DashscopeAsrProvider.UPLOADS_PATH).build().toString())
                .isEqualTo("https://example.invalid/api/v1/uploads");
        assertThat(factory.expand(DashscopeAsrProvider.TRANSCRIPTION_PATH).toString())
                .isEqualTo("https://example.invalid/api/v1/services/audio/asr/transcription");
        assertThat(factory.expand(DashscopeAsrProvider.TASK_PATH, "task-123").toString())
                .isEqualTo("https://example.invalid/api/v1/tasks/task-123");
    }

    @Test
    void pollsATranscriptionTaskWithPostAndWithoutTheAsyncHeader() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/tasks/task-123", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders()).doesNotContainKey("X-DashScope-Async");
            byte[] response = "{\"output\":{\"task_status\":\"RUNNING\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1");
        DashscopeAsrProvider provider = new DashscopeAsrProvider(properties, null, new ObjectMapper());

        assertThat(provider.poll("task-123").status()).isEqualTo(AsrProvider.AsrPollResult.Status.RUNNING);
    }

    @Test
    void returnsTheProviderErrorWhenASubtaskFails() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/tasks/task-123", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            byte[] response = ("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{"
                    + "\"subtask_status\":\"FAILED\",\"code\":\"InvalidFile.DownloadFailed\","
                    + "\"message\":\"The audio file cannot be downloaded.\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1");
        DashscopeAsrProvider provider = new DashscopeAsrProvider(properties, null, new ObjectMapper());

        AsrProvider.AsrPollResult result = provider.poll("task-123");

        assertThat(result.status()).isEqualTo(AsrProvider.AsrPollResult.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("InvalidFile.DownloadFailed");
        assertThat(result.errorMessage()).isEqualTo("The audio file cannot be downloaded.");
    }

    @Test
    void preservesTheExactSignedTranscriptUrlWhenDownloadingTheResult() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String resultPath = "/result?Expires=1739525481&Signature=one%2Btwo%3D";
        server.createContext("/api/v1/tasks/task-123", exchange -> {
            String transcriptUrl = "http://localhost:" + server.getAddress().getPort() + resultPath;
            byte[] response = ("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{"
                    + "\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"" + transcriptUrl + "\"}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/result", exchange -> {
            assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("Expires=1739525481&Signature=one%2Btwo%3D");
            byte[] response = "{\"properties\":{\"channels\":[0]},\"transcripts\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api/v1");
        DashscopeAsrProvider provider = new DashscopeAsrProvider(properties, null, new ObjectMapper());

        assertThat(provider.poll("task-123").status()).isEqualTo(AsrProvider.AsrPollResult.Status.SUCCEEDED);
    }
}
