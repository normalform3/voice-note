package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "true")
public class DashscopeTextEmbeddingClient implements TextEmbeddingClient {
    private final AppProperties properties;
    private final RestClient client;

    public DashscopeTextEmbeddingClient(AppProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }

    @Override public List<List<Double>> embedDocuments(List<String> texts) { return embed(texts, "document"); }
    @Override public List<Double> embedQuery(String text) { return embed(List.of(text), "query").get(0); }
    @Override public EmbeddedDocument embedDocumentWithUsage(String text) {
        try {
            JsonNode response = client.post().uri("/services/embeddings/text-embedding/text-embedding")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getDashscope().getEmbeddingModel(),
                            "input", Map.of("texts", List.of(text)),
                            "parameters", Map.of("text_type", "document", "dimension", properties.getDashscope().getEmbeddingDimension())))
                    .retrieve().body(JsonNode.class);
            JsonNode embedding = response == null ? null : response.path("output").path("embeddings").path(0);
            if (embedding == null || embedding.isMissingNode()) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_RESPONSE_INVALID", "DashScope returned no embedding");
            List<Double> values = vector(embedding);
            int tokens = response.path("usage").path("prompt_tokens").asInt(-1);
            return new EmbeddedDocument(values, tokens < 0 ? null : tokens);
        } catch (ProviderException exception) { throw exception; }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "EMBEDDING_RATE_LIMIT", "DashScope rate limited embeddings");
            if (exception.getStatusCode().is5xxServerError()) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "EMBEDDING_SERVER_ERROR", "Embedding outcome is unknown");
            throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_REJECTED", exception.getResponseBodyAsString());
        } catch (RuntimeException exception) { throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "EMBEDDING_NETWORK", "Embedding outcome is unknown"); }
    }

    private List<List<Double>> embed(List<String> texts, String textType) {
        if (texts.isEmpty()) return List.of();
        List<List<Double>> output = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 10) {
            List<String> batch = texts.subList(start, Math.min(start + 10, texts.size()));
            try {
                JsonNode response = client.post().uri("/services/embeddings/text-embedding/text-embedding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("model", properties.getDashscope().getEmbeddingModel(),
                                "input", Map.of("texts", batch),
                                "parameters", Map.of("text_type", textType, "dimension", properties.getDashscope().getEmbeddingDimension())))
                        .retrieve().body(JsonNode.class);
                JsonNode embeddings = response == null ? null : response.path("output").path("embeddings");
                if (embeddings == null || !embeddings.isArray() || embeddings.size() != batch.size()) {
                    throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_RESPONSE_INVALID", "DashScope returned incomplete embeddings");
                }
                for (JsonNode embedding : embeddings) output.add(vector(embedding));
            } catch (ProviderException exception) { throw exception; }
            catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() == 429) throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "EMBEDDING_RATE_LIMIT", "DashScope rate limited embeddings");
                if (exception.getStatusCode().is5xxServerError()) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "EMBEDDING_SERVER_ERROR", "Embedding outcome is unknown");
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_REJECTED", exception.getResponseBodyAsString());
            } catch (RuntimeException exception) {
                throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "EMBEDDING_NETWORK", "Embedding outcome is unknown");
            }
        }
        return output;
    }
    private List<Double> vector(JsonNode embedding) {
        List<Double> values = new ArrayList<>();
        for (JsonNode value : embedding.path("embedding")) values.add(value.asDouble());
        if (values.size() != properties.getDashscope().getEmbeddingDimension()) {
            throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_DIMENSION_INVALID", "DashScope returned an unexpected embedding dimension");
        }
        return values;
    }
}
