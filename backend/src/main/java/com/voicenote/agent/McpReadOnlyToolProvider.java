package com.voicenote.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.AgentModelClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

@Component
public class McpReadOnlyToolProvider {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final List<McpSyncClient> clients = new ArrayList<>();
    private List<AgentTool> tools = List.of();

    public McpReadOnlyToolProvider(AppProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }

    @PostConstruct
    void initialize() {
        if (!properties.getMcp().isEnabled()) return;
        try {
            List<ServerConfig> servers = mapper.readValue(properties.getMcp().getServers(), new TypeReference<>() { });
            List<AgentTool> discovered = new ArrayList<>();
            for (ServerConfig server : servers) {
                validate(server);
                try { discovered.addAll(connect(server)); }
                catch (RuntimeException ignored) { /* An unavailable optional MCP server must not break local knowledge Q&A. */ }
            }
            tools = List.copyOf(discovered);
        } catch (Exception exception) { throw new IllegalStateException("Cannot initialize configured MCP servers", exception); }
    }

    public List<AgentTool> tools() { return tools; }

    private List<AgentTool> connect(ServerConfig config) {
        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport.builder(config.baseUrl())
                .endpoint(config.endpoint() == null || config.endpoint().isBlank() ? "/mcp" : config.endpoint())
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .connectTimeout(Duration.ofSeconds(properties.getMcp().getRequestTimeoutSeconds()));
        if (config.authorizationEnv() != null && !config.authorizationEnv().isBlank()) {
            String authorization = System.getenv(config.authorizationEnv());
            if (authorization == null || authorization.isBlank()) throw new IllegalArgumentException("Configured MCP authorization environment variable is missing");
            transportBuilder.requestBuilder(HttpRequest.newBuilder().header("Authorization", authorization));
        }
        McpSyncClient client = McpClient.sync(transportBuilder.build())
                .requestTimeout(Duration.ofSeconds(properties.getMcp().getRequestTimeoutSeconds())).build();
        try { client.initialize(); }
        catch (RuntimeException exception) { client.closeGracefully(); throw exception; }
        clients.add(client);
        Set<String> allowed = new HashSet<>(config.readOnlyTools() == null ? List.of() : config.readOnlyTools());
        List<AgentTool> output = new ArrayList<>();
        for (McpSchema.Tool tool : client.listTools().tools()) {
            if (allowed.contains(tool.name()) && tool.name().matches("[A-Za-z0-9_-]{1,64}")) output.add(new McpAgentTool(config.name(), client, tool, Set.copyOf(config.allowedSkills())));
        }
        return output;
    }

    private void validate(ServerConfig config) {
        if (config.name() == null || !config.name().matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("MCP server name must be a safe lowercase identifier");
        URI uri = URI.create(config.baseUrl());
        boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException("MCP servers must use HTTPS, except localhost development servers");
        }
        if (config.readOnlyTools() == null || config.readOnlyTools().isEmpty()) throw new IllegalArgumentException("MCP server must configure an explicit readOnlyTools allowlist");
        if (config.allowedSkills() == null || config.allowedSkills().isEmpty()) throw new IllegalArgumentException("MCP server must configure an explicit allowedSkills allowlist");
        if (config.authorizationEnv() != null && !config.authorizationEnv().matches("[A-Z][A-Z0-9_]{1,127}")) throw new IllegalArgumentException("MCP authorizationEnv must be a safe environment variable name");
    }

    @PreDestroy void close() { clients.forEach(McpSyncClient::closeGracefully); }

    public record ServerConfig(String name, String baseUrl, String endpoint, String authorizationEnv, List<String> readOnlyTools, List<String> allowedSkills) { }

    private final class McpAgentTool implements AgentTool {
        private final String publicName; private final McpSyncClient client; private final McpSchema.Tool tool; private final Set<String> configuredSkills;
        private McpAgentTool(String serverName, McpSyncClient client, McpSchema.Tool tool, Set<String> configuredSkills) {
            this.publicName = "mcp." + serverName + "." + tool.name(); this.client = client; this.tool = tool; this.configuredSkills = configuredSkills;
        }
        @Override public AgentModelClient.AgentToolDefinition definition() {
            return new AgentModelClient.AgentToolDefinition(publicName, "Call the deployment-approved read-only external tool " + publicName + ".", mapper.valueToTree(tool.inputSchema()));
        }
        @Override public Set<String> allowedSkillIds() { return Set.copyOf(configuredSkills); }
        @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
            String rawArguments;
            try { rawArguments = mapper.writeValueAsString(arguments); } catch (Exception exception) { throw new IllegalArgumentException("MCP arguments are not valid JSON"); }
            if (context.evidence().containsTranscriptExcerpt(rawArguments)) throw new IllegalArgumentException("Transcript content cannot be sent to external MCP tools");
            Map<String, Object> values = mapper.convertValue(arguments, new TypeReference<>() { });
            McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(tool.name())
                    .arguments(values)
                    .build();
            McpSchema.CallToolResult result = client.callTool(request);
            JsonNode payload = result.structuredContent() == null ? mapper.valueToTree(result.content()) : mapper.valueToTree(result.structuredContent());
            String serialized;
            try { serialized = mapper.writeValueAsString(payload); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize MCP tool result", exception); }
            int max = properties.getAgent().getMaxToolOutputBytes();
            serialized = AgentOutputLimits.truncateUtf8(serialized, max);
            String ref = context.evidence().registerExternal(publicName, null, serialized);
            var output = mapper.createObjectNode(); output.put("sourceRef", ref); output.put("tool", publicName); output.put("content", serialized);
            output.put("isError", Boolean.TRUE.equals(result.isError()));
            return ToolResult.value(output, "调用只读外部工具 " + publicName);
        }
    }
}
