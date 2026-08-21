package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
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
    void forcesFinalizeAnswerWhenItIsTheOnlyRemainingTool() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server = server(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"finalize_answer\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}", request);
        DashscopeAgentModelClient client = client();
        var tool = new AgentModelClient.AgentToolDefinition("finalize_answer", "Submit answer",
                mapper.readTree("{\"type\":\"object\",\"properties\":{}}"));

        client.next(List.of(AgentModelClient.AgentMessage.user("提交答案")), List.of(tool), true);

        assertThat(request.get().path("tool_choice").path("type").asText()).isEqualTo("function");
        assertThat(request.get().path("tool_choice").path("function").path("name").asText()).isEqualTo("finalize_answer");
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

    @Test
    void rebuildsStreamedContentToolCallsAndUsageAcrossArbitraryInputBoundaries() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getDashscope().setApiKey("test-key"); properties.getDashscope().setChatModel("qwen-plus");
        properties.getDashscope().setBaseUrl("https://dashscope.aliyuncs.com/api/v1"); properties.getAgent().setTimeoutSeconds(5);
        DashscopeAgentModelClient client = new DashscopeAgentModelClient(properties, mapper);
        var firstCall = mapper.createObjectNode().put("index", 0).put("id", "call-1");
        firstCall.set("function", mapper.createObjectNode().put("name", "finalize_").put("arguments", "{\"blocks\":["));
        var firstDelta = mapper.createObjectNode().put("content", "正在");
        firstDelta.set("tool_calls", mapper.createArrayNode().add(firstCall));
        var firstChoice = mapper.createObjectNode(); firstChoice.set("delta", firstDelta);
        var firstRoot = mapper.createObjectNode(); firstRoot.set("choices", mapper.createArrayNode().add(firstChoice));
        String first = event(firstRoot);

        var secondCall = mapper.createObjectNode().put("index", 0);
        secondCall.set("function", mapper.createObjectNode().put("name", "answer").put("arguments", "]}"));
        var secondDelta = mapper.createObjectNode(); secondDelta.set("tool_calls", mapper.createArrayNode().add(secondCall));
        var secondChoice = mapper.createObjectNode().put("finish_reason", "tool_calls"); secondChoice.set("delta", secondDelta);
        var secondRoot = mapper.createObjectNode(); secondRoot.set("choices", mapper.createArrayNode().add(secondChoice));
        String second = event(secondRoot);
        String usage = event(mapper.createObjectNode().set("usage", mapper.createObjectNode().put("prompt_tokens", 9).put("completion_tokens", 4).put("total_tokens", 13)));
        byte[] payload = (first + second + usage + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new FilterInputStream(new ByteArrayInputStream(payload)) {
            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { return super.read(bytes, offset, Math.min(1, length)); }
        };
        List<String> argumentDeltas = new java.util.ArrayList<>();

        AgentModelClient.AgentModelTurn turn = client.readSse(oneByteAtATime, new AgentModelClient.StreamObserver() {
            @Override public void onToolCallDelta(int index, String id, String name, String arguments) { argumentDeltas.add(arguments); }
        });

        assertThat(turn.content()).isEqualTo("正在");
        assertThat(turn.toolCalls()).containsExactly(new AgentModelClient.AgentToolCall("call-1", "finalize_answer", "{\"blocks\":[]}"));
        assertThat(turn.usage()).isEqualTo(new AgentModelClient.AgentUsage(9, 4, 13));
        assertThat(argumentDeltas).containsExactly("{\"blocks\":[", "]}");
    }

    private String event(JsonNode value) throws Exception { return "data: " + mapper.writeValueAsString(value) + "\n\n"; }

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
