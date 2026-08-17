package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpReadOnlyToolProviderTest {
    @Test
    void disabledMcpRegistersNoToolsAndReportsDisabledStatus() {
        AppProperties properties = new AppProperties();
        McpReadOnlyToolProvider provider = new McpReadOnlyToolProvider(properties, new ObjectMapper());

        provider.initialize();

        assertThat(provider.tools()).isEmpty();
        assertThat(provider.statuses()).containsExactly(new McpReadOnlyToolProvider.ServerStatus("mcp", "DISABLED", false, java.util.List.of(), null));
    }

    @Test
    void invalidOptionalConfigurationDoesNotPreventToolProviderStartup() {
        AppProperties properties = new AppProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setServers("not-json");
        McpReadOnlyToolProvider provider = new McpReadOnlyToolProvider(properties, new ObjectMapper());

        provider.initialize();

        assertThat(provider.tools()).isEmpty();
        assertThat(provider.statuses()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("configuration");
            assertThat(value.connected()).isFalse();
            assertThat(value.failure()).isEqualTo("MCP server is unavailable");
        });
    }

    @Test
    void rejectsUnpinnedDingtalkPackageBeforeLaunchingTheOptionalChildProcess() {
        AppProperties properties = new AppProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setServers("[{\"name\":\"dingtalk\",\"transport\":\"STDIO\",\"command\":\"npx\",\"arguments\":[\"-y\",\"dingtalk-mcp@latest\"],\"readOnlyTools\":[\"getCalendarView\"],\"allowedSkills\":[\"meeting-summary\"]}]");
        McpReadOnlyToolProvider provider = new McpReadOnlyToolProvider(properties, new ObjectMapper());

        provider.initialize();

        assertThat(provider.tools()).isEmpty();
        assertThat(provider.statuses()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("dingtalk");
            assertThat(value.connected()).isFalse();
            assertThat(value.failure()).isEqualTo("Invalid MCP configuration or server response");
        });
    }
}
