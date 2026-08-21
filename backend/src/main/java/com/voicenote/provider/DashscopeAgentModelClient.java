package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "true")
public class DashscopeAgentModelClient implements AgentModelClient {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;

    public DashscopeAgentModelClient(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(10)); requests.setReadTimeout(Duration.ofSeconds(properties.getAgent().getTimeoutSeconds()));
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getCompatibleBaseUrl())
                .requestFactory(requests).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }

    @Override
    public AgentModelTurn next(List<AgentMessage> messages, List<AgentToolDefinition> tools, boolean requireTool) {
        try {
            Map<String, Object> body = requestBody(messages, tools, requireTool);
            JsonNode response = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            JsonNode choice = response == null ? null : response.path("choices").path(0);
            JsonNode message = choice == null ? null : choice.path("message");
            if (message == null || message.isMissingNode()) throw invalid();
            List<AgentToolCall> calls = new ArrayList<>();
            JsonNode callNodes = message.path("tool_calls");
            if (callNodes.isArray()) for (JsonNode call : callNodes) {
                String id = call.path("id").asText(null); String name = call.path("function").path("name").asText(null);
                String arguments = call.path("function").path("arguments").asText(null);
                if (id == null || name == null || arguments == null) throw invalid();
                mapper.readTree(arguments); calls.add(new AgentToolCall(id, name, arguments));
            }
            JsonNode usage = response.path("usage");
            AgentUsage parsedUsage = usage.isObject() ? new AgentUsage(nullableInt(usage, "prompt_tokens"), nullableInt(usage, "completion_tokens"), nullableInt(usage, "total_tokens")) : null;
            return new AgentModelTurn(message.path("content").isNull() ? null : message.path("content").asText(null), List.copyOf(calls), choice.path("finish_reason").asText(null), parsedUsage);
        } catch (ProviderException exception) { throw exception; }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "AGENT_RATE_LIMIT", "DashScope rate limited the agent");
            if (exception.getStatusCode().is5xxServerError()) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "AGENT_SERVER_ERROR", "Agent model outcome is unknown");
            throw rejected(exception);
        } catch (Exception exception) {
            throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "AGENT_NETWORK", "Agent model outcome is unknown");
        }
    }

    @Override
    public AgentModelTurn nextStreaming(List<AgentMessage> messages, List<AgentToolDefinition> tools,
                                        boolean requireTool, StreamObserver observer) {
        try {
            Map<String, Object> body = requestBody(messages, tools, requireTool);
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            return client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                    .body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION,
                                "AGENT_RATE_LIMIT", "DashScope rate limited the agent");
                        if (status >= 500) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION,
                                "AGENT_SERVER_ERROR", "Agent model outcome is unknown");
                        if (status >= 400) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION,
                                "AGENT_REJECTED", "DashScope rejected the streamed agent request (HTTP " + status + ")");
                        return readSse(response.getBody(), observer == null ? new StreamObserver() { } : observer);
                    });
        } catch (ProviderException exception) { throw exception; }
        catch (Exception exception) {
            throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "AGENT_NETWORK", "Agent model outcome is unknown");
        }
    }

    AgentModelTurn readSse(InputStream input, StreamObserver observer) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, MutableToolCall> calls = new TreeMap<>();
        String finishReason = null;
        AgentUsage usage = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode chunk = mapper.readTree(data);
                JsonNode usageNode = chunk.path("usage");
                if (usageNode.isObject() && !usageNode.isEmpty()) {
                    usage = new AgentUsage(nullableInt(usageNode, "prompt_tokens"), nullableInt(usageNode, "completion_tokens"), nullableInt(usageNode, "total_tokens"));
                }
                JsonNode choice = chunk.path("choices").path(0);
                if (choice.isMissingNode()) continue;
                if (choice.path("finish_reason").isTextual()) finishReason = choice.path("finish_reason").asText();
                JsonNode delta = choice.path("delta");
                if (delta.path("content").isTextual()) {
                    String value = delta.path("content").asText(); content.append(value); observer.onContentDelta(value);
                }
                JsonNode toolCalls = delta.path("tool_calls");
                if (!toolCalls.isArray()) continue;
                for (JsonNode call : toolCalls) {
                    int index = call.path("index").asInt(0);
                    MutableToolCall target = calls.computeIfAbsent(index, ignored -> new MutableToolCall());
                    String id = call.path("id").asText("");
                    String name = call.path("function").path("name").asText("");
                    String arguments = call.path("function").path("arguments").asText("");
                    target.id.append(id); target.name.append(name); target.arguments.append(arguments);
                    observer.onToolCallDelta(index, id, name, arguments);
                }
            }
        }
        List<AgentToolCall> parsedCalls = new ArrayList<>();
        for (MutableToolCall call : calls.values()) {
            if (call.id.isEmpty() || call.name.isEmpty()) throw invalid();
            String arguments = call.arguments.toString(); mapper.readTree(arguments);
            parsedCalls.add(new AgentToolCall(call.id.toString(), call.name.toString(), arguments));
        }
        return new AgentModelTurn(content.isEmpty() ? null : content.toString(), List.copyOf(parsedCalls), finishReason, usage);
    }

    private Map<String, Object> requestBody(List<AgentMessage> messages, List<AgentToolDefinition> tools, boolean requireTool) {
        List<Map<String, Object>> wireMessages = new ArrayList<>();
        for (AgentMessage message : messages) {
            Map<String, Object> wire = new LinkedHashMap<>(); wire.put("role", message.role());
            if (message.content() != null) wire.put("content", message.content());
            if (message.toolCallId() != null) wire.put("tool_call_id", message.toolCallId());
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                wire.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                        "id", call.id(), "type", "function", "function", Map.of("name", call.name(), "arguments", call.arguments()))).toList());
            }
            wireMessages.add(wire);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getDashscope().getChatModel()); body.put("messages", wireMessages); body.put("temperature", 0.1);
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function", Map.of(
                    "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()))).toList());
            boolean forceFinalize = requireTool && tools.size() == 1 && "finalize_answer".equals(tools.get(0).name());
            body.put("tool_choice", forceFinalize
                    ? Map.of("type", "function", "function", Map.of("name", "finalize_answer"))
                    : "auto");
        }
        return body;
    }

    private static final class MutableToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private static ProviderException invalid() {
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "AGENT_RESPONSE_INVALID", "DashScope returned an invalid agent response");
    }

    private ProviderException rejected(RestClientResponseException exception) {
        String providerCode = null;
        String providerMessage = null;
        try {
            JsonNode body = mapper.readTree(exception.getResponseBodyAsString());
            JsonNode error = body.path("error");
            providerCode = error.path("code").asText(null);
            providerMessage = error.path("message").asText(null);
            if (providerCode == null) providerCode = body.path("code").asText(null);
            if (providerMessage == null) providerMessage = body.path("message").asText(null);
        } catch (Exception ignored) { }
        StringBuilder message = new StringBuilder("DashScope rejected the agent request (HTTP ")
                .append(exception.getStatusCode().value());
        if (providerCode != null && !providerCode.isBlank()) message.append(", ").append(shorten(providerCode));
        message.append(')');
        if (providerMessage != null && !providerMessage.isBlank()) message.append(": ").append(shorten(providerMessage));
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "AGENT_REJECTED", message.toString());
    }

    private static String shorten(String value) {
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }
    private static Integer nullableInt(JsonNode node, String name) { return node.path(name).isIntegralNumber() ? node.path(name).asInt() : null; }
}
