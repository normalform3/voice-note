package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashscopeAgentModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesDashscopeCompatibleAutoToolChoiceWhenAToolIsRequired() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server = server(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"继续检索\"},\"finish_reason\":\"stop\"}]}", request);
        DashscopeAgentModelClient client = client();
        var tool = new AgentModelClient.AgentToolDefinition("document_list", "List documents",
                mapper.readTree("{\"type\":\"object\",\"properties\":{}}"));

        client.next(List.of(AgentModelClient.AgentMessage.user("总结资料")), List.of(tool), true);

        assertThat(request.get().path("tool_choice").asText()).isEqualTo("auto");
        assertThat(request.get().path("tools").path(0).path("function").path("name").asText()).isEqualTo("document_list");
    }

    @Test
    void includesDashscopeErrorCodeAndMessageInARejectedRequest() throws Exception {
        server = server(400, "{\"error\":{\"code\":\"invalid_parameter_error\",\"message\":\"tool_choice must be auto or none\"}}", new AtomicReference<>());
        DashscopeAgentModelClient client = client();
        var tool = new AgentModelClient.AgentToolDefinition("document_list", "List documents",
                mapper.readTree("{\"type\":\"object\"}"));

        assertThatThrownBy(() -> client.next(List.of(AgentModelClient.AgentMessage.user("总结资料")), List.of(tool), true))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid_parameter_error")
                .hasMessageContaining("tool_choice must be auto or none");
    }

    private DashscopeAgentModelClient client() {
        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key");
        properties.getDashscope().setChatModel("qwen-plus");
        properties.getDashscope().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        properties.getAgent().setTimeoutSeconds(5);
        return new DashscopeAgentModelClient(properties, mapper);
    }

    private HttpServer server(int status, String responseBody, AtomicReference<JsonNode> request) throws Exception {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        value.start();
        return value;
    }
}
