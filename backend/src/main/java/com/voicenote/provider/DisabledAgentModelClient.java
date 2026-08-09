package com.voicenote.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledAgentModelClient implements AgentModelClient {
    @Override public AgentModelTurn next(List<AgentMessage> messages, List<AgentToolDefinition> tools, boolean requireTool) {
        throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "AGENT_PROVIDER_DISABLED", "DashScope is disabled; configure DASHSCOPE_ENABLED and DASHSCOPE_API_KEY");
    }
}
