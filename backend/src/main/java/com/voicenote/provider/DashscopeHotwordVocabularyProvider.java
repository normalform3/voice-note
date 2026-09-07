package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "true")
public class DashscopeHotwordVocabularyProvider implements HotwordVocabularyProvider {
    static final String CUSTOMIZATION_PATH = "/services/audio/asr/customization";
    private final ObjectMapper mapper;
    private final RestClient client;

    public DashscopeHotwordVocabularyProvider(AppProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }

    @Override public String create(String prefix, String targetModel, List<Entry> entries) {
        JsonNode response = call(input("create_vocabulary").put("target_model", targetModel).put("prefix", prefix)
                .set("vocabulary", vocabulary(entries)));
        String id = response.path("output").path("vocabulary_id").asText(null);
        if (id == null || id.isBlank()) throw failure("HOTWORD_ID_MISSING", "DashScope 未返回热词词表 ID");
        return id;
    }

    @Override public Vocabulary query(String vocabularyId) {
        JsonNode output = call(input("query_vocabulary").put("vocabulary_id", vocabularyId)).path("output");
        List<Entry> entries = new ArrayList<>();
        output.path("vocabulary").forEach(value -> entries.add(new Entry(value.path("text").asText(), value.path("weight").asInt(), value.path("lang").asText(null))));
        return new Vocabulary(vocabularyId, output.path("target_model").asText(null), List.copyOf(entries), output.path("status").asText(null));
    }

    @Override public String findIdByPrefix(String prefix) {
        JsonNode output = call(input("list_vocabulary").put("prefix", prefix).put("page_index", 0).put("page_size", 10)).path("output");
        JsonNode values = output.path("vocabularies");
        if (!values.isArray()) values = output.path("vocabulary_list");
        if (!values.isArray()) return null;
        for (JsonNode value : values) {
            String candidatePrefix = value.path("prefix").asText(null);
            if (candidatePrefix == null || prefix.equals(candidatePrefix)) {
                String id = value.path("vocabulary_id").asText(null);
                if (id != null && !id.isBlank()) return id;
            }
        }
        return null;
    }

    @Override public void update(String vocabularyId, List<Entry> entries) {
        call(input("update_vocabulary").put("vocabulary_id", vocabularyId).set("vocabulary", vocabulary(entries)));
    }

    @Override public void delete(String vocabularyId) {
        call(input("delete_vocabulary").put("vocabulary_id", vocabularyId));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode input(String action) {
        return mapper.createObjectNode().put("action", action);
    }
    private JsonNode vocabulary(List<Entry> entries) {
        var output = mapper.createArrayNode();
        for (Entry entry : entries) output.add(mapper.createObjectNode().put("text", entry.text()).put("weight", entry.weight()).put("lang", entry.language()));
        return output;
    }
    private JsonNode call(JsonNode input) {
        try {
            var body = mapper.createObjectNode().put("model", "speech-biasing").set("input", input);
            JsonNode response = client.post().uri(CUSTOMIZATION_PATH).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            if (response == null) throw failure("HOTWORD_RESPONSE_MISSING", "DashScope 未返回热词响应");
            return response;
        } catch (ProviderException exception) { throw exception; }
        catch (RestClientResponseException exception) {
            throw classifyHttp(exception.getStatusCode().value(), exception.getResponseBodyAsString());
        } catch (RuntimeException exception) {
            throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "HOTWORD_PROVIDER_UNAVAILABLE", "暂时无法连接 DashScope 热词服务");
        }
    }
    static ProviderException classifyHttp(int status, String body) {
        String response = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        if (response.contains("throttling.allocationquota") || response.contains("free allocated quota exceeded")) {
            return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "HOTWORD_PROVIDER_CAPACITY_EXHAUSTED",
                    "DashScope 账号的热词词表额度已满，请先删除账号中不再使用的词表");
        }
        if (status == 429) {
            return new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "HOTWORD_PROVIDER_RATE_LIMIT",
                    "DashScope 热词服务请求过于频繁");
        }
        ProviderException.Kind kind = status >= 500 ? ProviderException.Kind.RETRYABLE_REJECTION : ProviderException.Kind.FINAL_REJECTION;
        return new ProviderException(kind, "HOTWORD_PROVIDER_REJECTED", "DashScope 拒绝了热词操作（HTTP " + status + "）");
    }
    private static ProviderException failure(String code, String message) {
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, code, message);
    }
}
