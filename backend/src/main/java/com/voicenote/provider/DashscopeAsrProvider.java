package com.voicenote.provider;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.AudioBlob;
import com.voicenote.service.ObjectStorage;
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
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();
    static final String UPLOADS_PATH = "/uploads";
    static final String TRANSCRIPTION_PATH = "/services/audio/asr/transcription";
    static final String TASK_PATH = "/tasks/{id}";
    private final AppProperties properties;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final RestClient client;

    public DashscopeAsrProvider(AppProperties properties, ObjectStorage storage, ObjectMapper mapper) {
        this.properties = properties; this.storage = storage; this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(properties.getDashscope().getApiBaseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDashscope().getApiKey()).build();
    }

    @Override
    public AsrSubmission submit(AudioBlob audio, AsrOptions options) {
        try {
            if (!"paraformer-v2".equals(properties.getDashscope().getAsrModel())) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_MODEL_UNSUPPORTED", "VoiceNote requires paraformer-v2 for speaker-aware transcription");
            }
            JsonNode policy = client.get().uri(uri -> uri.path(UPLOADS_PATH).queryParam("action", "getPolicy").queryParam("model", properties.getDashscope().getAsrModel()).build())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve().body(JsonNode.class);
            if (policy == null || policy.path("data").isMissingNode()) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_UPLOAD_POLICY", "DashScope did not return an upload policy");
            JsonNode data = policy.path("data");
            String key = data.path("upload_dir").asText() + "/" + safeName(audio.getOriginalFilename());
            uploadToDashscope(data, key, audio);
            String inputUrl = "oss://" + key;
            var bodyNode = mapper.createObjectNode();
            bodyNode.put("model", properties.getDashscope().getAsrModel());
            bodyNode.set("input", mapper.createObjectNode().set("file_urls", mapper.createArrayNode().add(inputUrl)));
            boolean diarizationEnabled = options == null || options.diarizationEnabled();
            var parameters = mapper.createObjectNode().put("diarization_enabled", diarizationEnabled);
            if (options != null && options.languageHints() != null && !options.languageHints().isEmpty()) {
                parameters.set("language_hints", mapper.valueToTree(options.languageHints()));
            }
            if (options != null && options.speakerCount() != null) parameters.put("speaker_count", options.speakerCount());
            bodyNode.set("parameters", parameters);
            String body = mapper.writeValueAsString(bodyNode);
            JsonNode response = client.post().uri(TRANSCRIPTION_PATH)
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
            JsonNode response = client.post().uri(TASK_PATH, providerTaskId).retrieve().body(JsonNode.class);
            String status = response.path("output").path("task_status").asText("PENDING");
            if ("PENDING".equals(status) || "RUNNING".equals(status)) return new AsrPollResult(AsrPollResult.Status.RUNNING, null, null, List.of());
            if (!"SUCCEEDED".equals(status)) return new AsrPollResult(AsrPollResult.Status.FAILED, response.path("code").asText("ASR_FAILED"), response.path("message").asText("ASR failed"), List.of());
            JsonNode results = response.path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                return new AsrPollResult(AsrPollResult.Status.FAILED, "ASR_RESULT_MISSING", "ASR completed without a subtask result", List.of());
            }
            JsonNode subtask = results.get(0);
            String subtaskStatus = subtask.path("subtask_status").asText(null);
            if (subtaskStatus != null && !"SUCCEEDED".equals(subtaskStatus)) {
                String errorCode = subtask.path("code").asText(null);
                String errorMessage = subtask.path("message").asText(null);
                return new AsrPollResult(AsrPollResult.Status.FAILED,
                        errorCode == null || errorCode.isBlank() ? "ASR_SUBTASK_FAILED" : errorCode,
                        errorMessage == null || errorMessage.isBlank() ? "DashScope ASR subtask failed" : errorMessage,
                        List.of());
            }
            String transcriptionUrl = subtask.path("transcription_url").asText(null);
            if (transcriptionUrl == null) return new AsrPollResult(AsrPollResult.Status.FAILED, "ASR_RESULT_MISSING", "ASR completed without a transcript URL", List.of());
            JsonNode transcript = RestClient.create().get().uri(URI.create(transcriptionUrl)).retrieve().body(JsonNode.class);
            ParsedTranscript parsed = parseTranscript(transcript);
            return new AsrPollResult(AsrPollResult.Status.SUCCEEDED, null, null, parsed.segments(), parsed.metadata());
        } catch (RestClientResponseException exception) { throw classifyHttp(exception.getStatusCode().value(), exception.getResponseBodyAsString()); }
        catch (RuntimeException exception) { throw new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "DASHSCOPE_POLL_FAILED", "Could not poll the ASR task"); }
    }

    private void uploadToDashscope(JsonNode policy, String key, AudioBlob audio) throws IOException {
        String boundary = "----VoiceNote" + UUID.randomUUID();
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
    static ProviderException classifyHttp(int status, String body) {
        String providerCode = providerCode(body);
        if ("AllocationQuota.FreeTierOnly".equals(providerCode)) {
            return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_QUOTA_EXHAUSTED",
                    "DashScope 免费额度已耗尽。请在模型服务控制台充值，或关闭“仅使用免费额度”后重新提交转写。");
        }
        if (status == 429) return new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "DASHSCOPE_RATE_LIMIT", "DashScope rate limited the request");
        if (status >= 500) return new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "DASHSCOPE_SERVER_ERROR", "DashScope may have received the submission");
        String detail = providerMessage(body);
        String message = detail == null ? "DashScope rejected the request (HTTP " + status + ")" : "DashScope rejected the request: " + detail;
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "DASHSCOPE_REQUEST_REJECTED", message);
    }
    private static String providerCode(String body) { return providerErrorField(body, "code"); }
    private static String providerMessage(String body) { return providerErrorField(body, "message"); }
    private static String providerErrorField(String body, String field) {
        if (body == null || body.isBlank()) return null;
        try {
            String value = ERROR_MAPPER.readTree(body).path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }
    private static ParsedTranscript parseTranscript(JsonNode transcript) {
        List<AsrSegment> output = new ArrayList<>();
        JsonNode transcripts = transcript.path("transcripts");
        if (!transcripts.isArray()) return new ParsedTranscript(List.of(), new AsrAudioMetadata(null, null));
        for (JsonNode channel : transcripts) for (JsonNode sentence : channel.path("sentences")) {
            JsonNode speaker = sentence.path("speaker_id");
            String speakerId = speaker.isMissingNode() || speaker.isNull() || speaker.asText().isBlank() ? null : "SPEAKER_" + speaker.asText();
            output.add(new AsrSegment(speakerId,
                    sentence.path("begin_time").asLong(), sentence.path("end_time").asLong(), sentence.path("text").asText()));
        }
        JsonNode properties = transcript.path("properties");
        JsonNode channels = properties.path("channels");
        Integer channelCount = channels.isArray() ? channels.size() : null;
        long duration = properties.has("original_duration_in_milliseconds") ? properties.path("original_duration_in_milliseconds").asLong(-1)
                : properties.path("original_duration").asLong(-1);
        return new ParsedTranscript(List.copyOf(output), new AsrAudioMetadata(channelCount, duration < 0 ? null : duration));
    }
    private record ParsedTranscript(List<AsrSegment> segments, AsrAudioMetadata metadata) { }
}
