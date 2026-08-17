package com.voicenote.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.AgentModelClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class McpReadOnlyToolProvider {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z0-9_-]{1,48}");
    private static final Pattern SAFE_ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,127}");
    private static final Pattern SAFE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> WRITE_WORDS = Set.of("create", "update", "delete", "send", "write", "remove", "recall", "add", "set", "cancel");
    private static final Set<String> IDENTITY_ARGUMENTS = Set.of("userid", "userids", "useridlist", "staffid", "staffids", "corpid", "operatorid", "tenantid");
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final List<McpSyncClient> clients = new ArrayList<>();
    private List<AgentTool> tools = List.of();
    private List<ServerStatus> statuses = List.of();

    public McpReadOnlyToolProvider(AppProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }

    @PostConstruct
    void initialize() {
        if (!properties.getMcp().isEnabled()) {
            statuses = List.of(new ServerStatus("mcp", "DISABLED", false, List.of(), null));
            return;
        }
        List<ServerStatus> observed = new ArrayList<>();
        try {
            List<ServerConfig> servers = mapper.readValue(properties.getMcp().getServers(), new TypeReference<>() { });
            List<AgentTool> discovered = new ArrayList<>();
            for (ServerConfig server : servers) {
                try {
                    validate(server);
                    List<AgentTool> connected = connect(server);
                    discovered.addAll(connected);
                    observed.add(new ServerStatus(server.name(), transport(server), true,
                            connected.stream().map(value -> value.definition().name()).toList(), null));
                } catch (RuntimeException exception) {
                    observed.add(new ServerStatus(safeName(server), transport(server), false, List.of(), safeFailure(exception)));
                }
            }
            tools = List.copyOf(discovered);
        } catch (Exception exception) {
            tools = List.of();
            observed.add(new ServerStatus("configuration", "UNKNOWN", false, List.of(), safeFailure(exception)));
        }
        statuses = List.copyOf(observed);
    }

    public List<AgentTool> tools() { return tools; }
    public List<ServerStatus> statuses() { return statuses; }

    private List<AgentTool> connect(ServerConfig config) {
        McpSyncClient client = McpClient.sync(clientTransport(config))
                .requestTimeout(Duration.ofSeconds(properties.getMcp().getRequestTimeoutSeconds())).build();
        try {
            client.initialize();
            Set<String> allowed = new HashSet<>(config.readOnlyTools() == null ? List.of() : config.readOnlyTools());
            List<AgentTool> output = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                if (allowed.contains(tool.name()) && SAFE_TOOL_NAME.matcher(tool.name()).matches() && readOnlyName(tool.name())) {
                    output.add(new McpAgentTool(config.name(), client, tool, Set.copyOf(config.allowedSkills())));
                }
            }
            clients.add(client);
            return output;
        } catch (RuntimeException exception) {
            client.closeGracefully();
            throw exception;
        }
    }

    private io.modelcontextprotocol.spec.McpClientTransport clientTransport(ServerConfig config) {
        if ("STDIO".equals(transport(config))) {
            Map<String, String> environment = new LinkedHashMap<>();
            for (Map.Entry<String, String> mapping : values(config.environment()).entrySet()) {
                String value = System.getenv(mapping.getValue());
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Configured MCP child environment variable is missing");
                environment.put(mapping.getKey(), value);
            }
            return new StdioClientTransport(ServerParameters.builder(config.command()).args(config.arguments()).env(environment).build(),
                    new JacksonMcpJsonMapper(mapper));
        }
        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport.builder(config.baseUrl())
                .endpoint(config.endpoint() == null || config.endpoint().isBlank() ? "/mcp" : config.endpoint())
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .connectTimeout(Duration.ofSeconds(properties.getMcp().getRequestTimeoutSeconds()));
        if (config.authorizationEnv() != null && !config.authorizationEnv().isBlank()) {
            String authorization = System.getenv(config.authorizationEnv());
            if (authorization == null || authorization.isBlank()) throw new IllegalArgumentException("Configured MCP authorization environment variable is missing");
            transportBuilder.requestBuilder(HttpRequest.newBuilder().header("Authorization", authorization));
        }
        return transportBuilder.build();
    }

    private void validate(ServerConfig config) {
        if (config == null || config.name() == null || !SAFE_IDENTIFIER.matcher(config.name()).matches()) throw new IllegalArgumentException("MCP server name must be a safe lowercase identifier");
        if ("STDIO".equals(transport(config))) validateStdio(config); else validateHttp(config);
        if (config.readOnlyTools() == null || config.readOnlyTools().isEmpty()) throw new IllegalArgumentException("MCP server must configure an explicit readOnlyTools allowlist");
        if (config.allowedSkills() == null || config.allowedSkills().isEmpty()) throw new IllegalArgumentException("MCP server must configure an explicit allowedSkills allowlist");
        if (config.readOnlyTools().stream().anyMatch(value -> value == null || !SAFE_TOOL_NAME.matcher(value).matches() || !readOnlyName(value))) throw new IllegalArgumentException("MCP readOnlyTools may not contain write operations");
        if (config.allowedSkills().stream().anyMatch(value -> value == null || !value.matches("[a-z0-9_-]{1,80}"))) throw new IllegalArgumentException("MCP allowedSkills contains an invalid identifier");
    }

    private void validateHttp(ServerConfig config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) throw new IllegalArgumentException("HTTP MCP server baseUrl is required");
        URI uri = URI.create(config.baseUrl());
        boolean local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || (local && "http".equalsIgnoreCase(uri.getScheme())))) throw new IllegalArgumentException("MCP servers must use HTTPS, except localhost development servers");
        if (config.authorizationEnv() != null && !SAFE_ENVIRONMENT_NAME.matcher(config.authorizationEnv()).matches()) throw new IllegalArgumentException("MCP authorizationEnv must be a safe environment variable name");
    }

    private void validateStdio(ServerConfig config) {
        if (config.command() == null || !config.command().matches("[A-Za-z0-9._/-]{1,160}")) throw new IllegalArgumentException("STDIO MCP command is invalid");
        if (config.arguments() == null || config.arguments().isEmpty()) throw new IllegalArgumentException("STDIO MCP arguments are required");
        if (config.arguments().stream().anyMatch(value -> value == null || value.length() > 512 || value.indexOf('\u0000') >= 0)) throw new IllegalArgumentException("STDIO MCP argument is invalid");
        if ("dingtalk".equals(config.name()) && config.arguments().stream().noneMatch(value -> value.matches("dingtalk-mcp@[0-9][A-Za-z0-9._+-]*"))) {
            throw new IllegalArgumentException("DingTalk MCP must use a pinned dingtalk-mcp package version");
        }
        for (Map.Entry<String, String> mapping : values(config.environment()).entrySet()) {
            if (!SAFE_ENVIRONMENT_NAME.matcher(mapping.getKey()).matches() || !SAFE_ENVIRONMENT_NAME.matcher(mapping.getValue()).matches()) throw new IllegalArgumentException("STDIO MCP environment mapping is invalid");
        }
    }

    private static boolean readOnlyName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return WRITE_WORDS.stream().noneMatch(word -> lower.contains(word));
    }
    private static String transport(ServerConfig config) { return config == null || config.transport() == null || config.transport().isBlank() ? "HTTP" : config.transport().trim().toUpperCase(Locale.ROOT); }
    private static String safeName(ServerConfig config) { return config != null && config.name() != null && SAFE_IDENTIFIER.matcher(config.name()).matches() ? config.name() : "configuration"; }
    private static String safeFailure(Exception exception) { return exception instanceof IllegalArgumentException ? "Invalid MCP configuration or server response" : "MCP server is unavailable"; }
    private static Map<String, String> values(Map<String, String> values) { return values == null ? Map.of() : values; }

    @PreDestroy void close() { clients.forEach(McpSyncClient::closeGracefully); }

    public record ServerConfig(String name, String transport, String baseUrl, String endpoint, String authorizationEnv,
                               String command, List<String> arguments, Map<String, String> environment,
                               List<String> readOnlyTools, List<String> allowedSkills) { }
    public record ServerStatus(String name, String transport, boolean connected, List<String> tools, String failure) { }

    private final class McpAgentTool implements AgentTool {
        private final String publicName; private final McpSyncClient client; private final McpSchema.Tool tool; private final Set<String> configuredSkills;
        private McpAgentTool(String serverName, McpSyncClient client, McpSchema.Tool tool, Set<String> configuredSkills) {
            this.publicName = "mcp." + serverName + "." + tool.name(); this.client = client; this.tool = tool; this.configuredSkills = configuredSkills;
        }
        @Override public AgentModelClient.AgentToolDefinition definition() {
            return new AgentModelClient.AgentToolDefinition(publicName, "Call the deployment-approved read-only external tool " + publicName + ".", mapper.valueToTree(tool.inputSchema()));
        }
        @Override public Source source() { return Source.MCP; }
        @Override public Set<String> allowedSkillIds() { return Set.copyOf(configuredSkills); }
        @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
            String rawArguments;
            try { rawArguments = mapper.writeValueAsString(arguments); } catch (Exception exception) { throw new IllegalArgumentException("MCP arguments are not valid JSON"); }
            if (context.evidence().containsTranscriptExcerpt(rawArguments)) throw new IllegalArgumentException("Transcript content cannot be sent to external MCP tools");
            if (containsIdentitySelector(arguments)) throw new IllegalArgumentException("MCP tools cannot select another user or tenant");
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

        private boolean containsIdentitySelector(JsonNode value) {
            if (value == null || value.isNull()) return false;
            if (value.isArray()) {
                for (JsonNode item : value) if (containsIdentitySelector(item)) return true;
                return false;
            }
            if (!value.isObject()) return false;
            Iterator<String> fields = value.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String normalized = field.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (IDENTITY_ARGUMENTS.contains(normalized) || containsIdentitySelector(value.get(field))) return true;
            }
            return false;
        }
    }
}
