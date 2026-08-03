package com.voicenote.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledTextEmbeddingClient implements TextEmbeddingClient {
    private ProviderException disabled() { return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_PROVIDER_DISABLED", "DashScope is disabled; configure DASHSCOPE_ENABLED and DASHSCOPE_API_KEY"); }
    @Override public List<List<Double>> embedDocuments(List<String> texts) { throw disabled(); }
    @Override public List<Double> embedQuery(String text) { throw disabled(); }
}
