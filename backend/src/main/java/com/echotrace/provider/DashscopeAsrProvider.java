package com.echotrace.provider;

import com.echotrace.config.AppProperties;
import com.echotrace.domain.AudioBlob;
import com.echotrace.service.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.dashscope.enabled", havingValue = "true")
public class DashscopeAsrProvider implements AsrProvider {
    private final AppProperties properties;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final RestClient client;

    public DashscopeAsrProvider(AppProperties properties, ObjectStorage storage, ObjectMapper mapper) {
        this.properties = properties; this.storage = storage; this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }

    @Override
    public AsrSubmission submit(AudioBlob audio) {
        try {
            JsonNode policy = client.get().uri(uri -> uri.path("uploads").queryParam("action", "getPolicy").queryParam("model", properties.getDashscope().getAsrModel()).build())
                    .retrieve().body(JsonNode.class);
            if (policy == null || policy.path("data").isMissingNode()) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_UPLOAD_POLICY", "DashScope did not return an upload policy");
            JsonNode data = policy.path("data");
            String key = data.path("upload_dir").asText() + "/" + safeName(audio.getOriginalFilename());
            uploadToDashscope(data, key, audio);
            String inputUrl = "oss://" + key;
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", properties.getDashscope().getAsrModel())
                    .set("input", mapper.createObjectNode().set("file_urls", mapper.createArrayNode().add(inputUrl))));
            JsonNode response = client.post().uri("services/audio/asr/transcription")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-DashScope-Async", "enable")
                    .header("X-DashScope-OssResourceResolve", "enable")
                    .body(body).retrieve().body(JsonNode.class);
            String taskId = response == null ? null : response.path("output").path("task_id").asText(null);
            if (taskId == null) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_TASK_ID_MISSING", "DashScope accepted no ASR task id");
            return new AsrSubmission(taskId, inputUrl);
        } catch (ProviderException exception) { throw exception; }
        catch (RestClientResponseException exception) { throw classifyHttp(exception.getStatusCode().value(), exception.getResponseBodyAsString()); }
        catch (IOException exception) { throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "DASHSCOPE_IO", "ASR submission outcome is unknown after an I/O failure"); }
        catch (RuntimeException exception) { throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "DASHSCOPE_NETWORK", "ASR submission outcome is unknown after a network failure"); }
    }

    @Override
    public AsrPollResult poll(String providerTaskId) {
        try {
            JsonNode response = client.get().uri("tasks/{id}", providerTaskId).header("X-DashScope-Async", "enable").retrieve().body(JsonNode.class);
            String status = response.path("output").path("task_status").asText("PENDING");
            if ("PENDING".equals(status) || "RUNNING".equals(status)) return new AsrPollResult(AsrPollResult.Status.RUNNING, null, null, List.of());
            if (!"SUCCEEDED".equals(status)) return new AsrPollResult(AsrPollResult.Status.FAILED, response.path("code").asText("ASR_FAILED"), response.path("message").asText("ASR failed"), List.of());
            JsonNode results = response.path("output").path("results");
            String transcriptionUrl = results.isArray() && !results.isEmpty() ? results.get(0).path("transcription_url").asText(null) : null;
            if (transcriptionUrl == null) return new AsrPollResult(AsrPollResult.Status.FAILED, "ASR_RESULT_MISSING", "ASR completed without a transcript URL", List.of());
            JsonNode transcript = RestClient.create().get().uri(transcriptionUrl).retrieve().body(JsonNode.class);
            return new AsrPollResult(AsrPollResult.Status.SUCCEEDED, null, null, parseSegments(transcript));
        } catch (RestClientResponseException exception) { throw classifyHttp(exception.getStatusCode().value(), exception.getResponseBodyAsString()); }
        catch (RuntimeException exception) { throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "DASHSCOPE_POLL_FAILED", "Could not poll the ASR task"); }
    }

    private void uploadToDashscope(JsonNode policy, String key, AudioBlob audio) throws IOException {
        String boundary = "----EchoTrace" + UUID.randomUUID();
        HttpURLConnection connection = (HttpURLConnection) new URL(policy.path("upload_host").asText()).openConnection();
        connection.setDoOutput(true); connection.setRequestMethod("POST"); connection.setChunkedStreamingMode(8192);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream output = connection.getOutputStream(); InputStream input = storage.get(audio.getObjectKey())) {
            writeField(output, boundary, "OSSAccessKeyId", policy.path("oss_access_key_id").asText());
            writeField(output, boundary, "Signature", policy.path("signature").asText());
            writeField(output, boundary, "policy", policy.path("policy").asText());
            writeField(output, boundary, "x-oss-object-acl", policy.path("x_oss_object_acl").asText());
            writeField(output, boundary, "x-oss-forbid-overwrite", policy.path("x_oss_forbid_overwrite").asText());
            writeField(output, boundary, "key", key);
            writeField(output, boundary, "success_action_status", "200");
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safeName(audio.getOriginalFilename()) + "\"\r\nContent-Type: " + audio.getContentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            input.transferTo(output);
            output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (connection.getResponseCode() != 200) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_UPLOAD_FAILED", "DashScope temporary-file upload failed");
    }

    private static void writeField(OutputStream output, String boundary, String key, String value) throws IOException {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + key + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static String safeName(String input) { return input.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static ProviderException classifyHttp(int status, String body) {
        if (status == 429) return new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "DASHSCOPE_RATE_LIMIT", "DashScope rate limited the request");
        if (status >= 500) return new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "DASHSCOPE_SERVER_ERROR", "DashScope may have received the submission");
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_REQUEST_REJECTED", body == null || body.isBlank() ? "DashScope rejected the request" : body);
    }
    private static List<AsrSegment> parseSegments(JsonNode transcript) {
        List<AsrSegment> output = new ArrayList<>();
        JsonNode transcripts = transcript.path("transcripts");
        if (!transcripts.isArray()) return output;
        for (JsonNode channel : transcripts) for (JsonNode sentence : channel.path("sentences")) {
            output.add(new AsrSegment(sentence.path("speaker_id").isMissingNode() ? null : "Speaker " + sentence.path("speaker_id").asText(),
                    sentence.path("begin_time").asLong(), sentence.path("end_time").asLong(), sentence.path("text").asText()));
        }
        return output;
    }
}
