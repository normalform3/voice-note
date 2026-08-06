package com.voicenote.provider;

import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class DashscopeTextEmbeddingClientTest {
    @Test
    void usesTotalTokensWhenTheNativeEmbeddingResponseOmitsPromptTokens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2]}]},\"usage\":{\"total_tokens\":37}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AppProperties properties = new AppProperties();
            properties.getDashscope().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            properties.getDashscope().setApiKey("test-key");
            properties.getDashscope().setEmbeddingModel("text-embedding-v4");
            properties.getDashscope().setEmbeddingDimension(2);

            TextEmbeddingClient.EmbeddedDocument result = new DashscopeTextEmbeddingClient(properties).embedDocumentWithUsage("会议纪要");

            assertThat(result.promptTokens()).isEqualTo(37);
            assertThat(result.vector()).containsExactly(0.1, 0.2);
        } finally {
            server.stop(0);
        }
    }
}
