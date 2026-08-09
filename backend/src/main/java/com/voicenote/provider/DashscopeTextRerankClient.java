package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.knowledge.rerank-enabled", havingValue = "true")
public class DashscopeTextRerankClient implements TextRerankClient {
    private final AppProperties properties;
    private final RestClient client;
    public DashscopeTextRerankClient(AppProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5)); requests.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getApiBaseUrl())
                .requestFactory(requests).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }
    @Override public RerankResult rerank(String query, List<Candidate> candidates) {
        try {
            JsonNode response = client.post().uri("/services/rerank/text-rerank/text-rerank").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.getKnowledge().getRerankModel(),
                            "input", Map.of("query", query, "documents", candidates.stream().map(Candidate::text).toList()),
                            "parameters", Map.of("top_n", candidates.size(), "return_documents", false)))
                    .retrieve().body(JsonNode.class);
            JsonNode results = response == null ? null : response.path("output").path("results");
            if (results == null || !results.isArray()) throw new IllegalStateException("Rerank response did not include results");
            List<Ranked> ranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index >= 0 && index < candidates.size()) ranked.add(new Ranked(candidates.get(index).id(), result.path("relevance_score").asDouble()));
            }
            if (ranked.isEmpty()) throw new IllegalStateException("Rerank response was empty");
            return new RerankResult(List.copyOf(ranked), false, null);
        } catch (RuntimeException exception) {
            return new RerankResult(candidates.stream().sorted(Comparator.comparingDouble(Candidate::retrievalScore).reversed())
                    .map(value -> new Ranked(value.id(), value.retrievalScore())).toList(), true, "rerankUnavailable");
        }
    }
}
