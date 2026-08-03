package com.voicenote.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledAnalysisModelClient implements AnalysisModelClient {
    @Override public String complete(String prompt) { throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_PROVIDER_DISABLED", "DashScope is disabled; configure DASHSCOPE_ENABLED and DASHSCOPE_API_KEY"); }
}
