package com.voicenote.provider;

import com.voicenote.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "true")
public class DashscopeAnalysisModelClient implements AnalysisModelClient {
    private final AppProperties properties;
    private final RestClient client;
    public DashscopeAnalysisModelClient(AppProperties properties) {
        this.properties = properties;
        String base = properties.getDashscope().getBaseUrl().replace("/api/v1", "/compatible-mode/v1");
        this.client = RestClient.builder().baseUrl(base).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }
    @Override public String complete(String prompt) {
        try {
            JsonNode response = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getDashscope().getChatModel(), "messages", new Object[]{Map.of("role", "user", "content", prompt)}, "temperature", 0.1))
                    .retrieve().body(JsonNode.class);
            String content = response == null ? null : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_RESPONSE_INVALID", "DashScope returned no analysis content");
            return content;
        } catch (ProviderException exception) { throw exception; }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "ANALYSIS_RATE_LIMIT", "DashScope rate limited analysis");
            if (exception.getStatusCode().is5xxServerError()) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "ANALYSIS_SERVER_ERROR", "Analysis outcome is unknown");
            throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_REJECTED", exception.getResponseBodyAsString());
        } catch (RuntimeException exception) { throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "ANALYSIS_NETWORK", "Analysis outcome is unknown"); }
    }
}
