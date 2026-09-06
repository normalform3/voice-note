package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.RealtimeRecordingSession;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

@Component
public class RealtimeAsrGateway {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    public RealtimeAsrGateway(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, properties.getRealtimeAsr().getConnectTimeoutSeconds()))).build();
    }
    public Connection open(RealtimeRecordingSession session, List<String> languages, Consumer<Object> events) {
        Connection connection = new Connection(session, languages, events); connection.connect(false); return connection;
    }

    public final class Connection {
        private final RealtimeRecordingSession session;
        private final List<String> languages;
        private final Consumer<Object> events;
        private final AtomicLong receivedBytes = new AtomicLong();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger retries = new AtomicInteger();
        private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
        private final Object sendMonitor = new Object();
        private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
        private volatile WebSocket socket;
        private volatile boolean ready;
        private volatile boolean finishing;
        private volatile boolean closed;
        private volatile long gapStartedMs = -1;
        private Connection(RealtimeRecordingSession session, List<String> languages, Consumer<Object> events) {
            this.session = session; this.languages = languages; this.events = events;
        }
        private void connect(boolean retry) {
            if (closed || finishing) return;
            ready = false;
            events.accept(Map.of("type", "status", "status", retry ? "reconnecting" : "connecting", "attempt", retries.get()));
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, properties.getRealtimeAsr().getConnectTimeoutSeconds())))
                    .header("Authorization", "Bearer " + properties.getDashscope().getApiKey())
                    .buildAsync(URI.create(properties.getRealtimeAsr().getWsUrl()), new UpstreamListener())
                    .whenComplete((value, failure) -> { if (failure != null) reconnect("REALTIME_ASR_CONNECT_FAILED"); });
        }
        public void sendAudio(ByteBuffer bytes) {
            int length = bytes.remaining(); receivedBytes.addAndGet(length);
            WebSocket target = socket;
            if (!ready || target == null || closed || finishing) return;
            ByteBuffer copy = ByteBuffer.allocate(length); copy.put(bytes.slice()); copy.flip();
            enqueue(target, () -> target.sendBinary(copy, true));
        }
        public long durationMs() { return receivedBytes.get() * 1000L / Math.max(1L, session.getSampleRate() * 2L); }
        public void finish() {
            finishing = true;
            WebSocket target = socket;
            if (target == null || !ready) { events.accept(Map.of("type", "finished")); close(); return; }
            sendText(target, finishTask());
        }
        public void close() { closed = true; ready = false; WebSocket target = socket; if (target != null) target.abort(); }

        private void reconnect(String code) {
            if (closed || finishing || !reconnectScheduled.compareAndSet(false, true)) return;
            ready = false;
            if (gapStartedMs < 0) gapStartedMs = durationMs();
            int attempt = retries.incrementAndGet();
            if (attempt > 3) {
                long endedAt = durationMs();
                if (gapStartedMs >= 0 && endedAt > gapStartedMs) {
                    events.accept(Map.of("type", "gap", "beginMs", gapStartedMs, "endMs", endedAt, "reason", "realtime_asr_unavailable"));
                    gapStartedMs = -1;
                }
                events.accept(Map.of("type", "error", "code", code, "message", "实时字幕连接已中断，录音仍会继续归档", "recoverable", false));
                events.accept(Map.of("type", "status", "status", "degraded", "attempt", attempt));
                reconnectScheduled.set(false); return;
            }
            WebSocket target = socket; if (target != null) target.abort();
            long delayMs = switch (attempt) { case 1 -> 500; case 2 -> 1000; default -> 2000; };
            CompletableFuture.runAsync(() -> { reconnectScheduled.set(false); connect(true); }, CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
        }
        private void started(WebSocket webSocket) {
            socket = webSocket; ready = true; reconnectScheduled.set(false);
            if (gapStartedMs >= 0) {
                events.accept(Map.of("type", "gap", "beginMs", gapStartedMs, "endMs", durationMs(), "reason", "realtime_asr_reconnected"));
                gapStartedMs = -1;
            }
            events.accept(Map.of("type", "ready", "sessionId", session.getId()));
        }
        private String runTask() {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("format", "pcm"); parameters.put("sample_rate", session.getSampleRate()); parameters.put("language_hints", languages);
            parameters.put("punctuation_prediction_enabled", true); parameters.put("semantic_punctuation_enabled", false);
            parameters.put("max_sentence_silence", properties.getRealtimeAsr().getMaxSentenceSilenceMs());
            parameters.put("heartbeat", true); parameters.put("inverse_text_normalization_enabled", true);
            return json(Map.of("header", Map.of("action", "run-task", "task_id", UUID.randomUUID().toString(), "streaming", "duplex"),
                    "payload", Map.of("task_group", "audio", "task", "asr", "function", "recognition",
                            "model", properties.getRealtimeAsr().getModel(), "parameters", parameters, "input", Map.of())));
        }
        private String finishTask() {
            return json(Map.of("header", Map.of("action", "finish-task", "task_id", currentTaskId.get(), "streaming", "duplex"), "payload", Map.of("input", Map.of())));
        }
        private final AtomicReference<String> currentTaskId = new AtomicReference<>();
        private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
        private void sendText(WebSocket target, String value) { enqueue(target, () -> target.sendText(value, true)); }
        private void enqueue(WebSocket target, java.util.function.Supplier<CompletableFuture<WebSocket>> action) {
            synchronized (sendMonitor) {
                sendTail = sendTail.handle((ignored, previousFailure) -> null)
                        .thenCompose(ignored -> {
                            if (closed || socket != target) return CompletableFuture.completedFuture(null);
                            try { return action.get().thenApply(sent -> null); }
                            catch (RuntimeException exception) { return CompletableFuture.failedFuture(exception); }
                        });
                sendTail.whenComplete((ignored, failure) -> {
                    if (failure != null && socket == target) reconnect("REALTIME_ASR_SEND_FAILED");
                });
            }
        }

        private final class UpstreamListener implements WebSocket.Listener {
            private final StringBuilder text = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) {
                socket = webSocket; webSocket.request(1);
                String command = runTask();
                try { currentTaskId.set(mapper.readTree(command).path("header").path("task_id").asText()); }
                catch (Exception exception) { reconnect("REALTIME_ASR_PROTOCOL_FAILED"); return; }
                sendText(webSocket, command);
            }
            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) { String value = text.toString(); text.setLength(0); handle(webSocket, value); }
                webSocket.request(1); return CompletableFuture.completedFuture(null);
            }
            private void handle(WebSocket webSocket, String value) {
                if (socket != webSocket) return;
                try {
                    JsonNode event = mapper.readTree(value); String type = event.path("header").path("event").asText();
                    if ("task-started".equals(type)) { started(webSocket); return; }
                    if ("result-generated".equals(type)) {
                        JsonNode sentence = event.path("payload").path("output").path("sentence");
                        if (sentence.path("heartbeat").asBoolean(false)) return;
                        String content = sentence.path("text").asText("").trim(); if (content.isEmpty()) return;
                        Map<String, Object> output = new LinkedHashMap<>(); output.put("type", "realtime_transcript");
                        output.put("sequence", sequence.incrementAndGet()); output.put("final", sentence.path("sentence_end").asBoolean(false));
                        output.put("beginMs", sentence.path("begin_time").asLong(0));
                        if (!sentence.path("end_time").isNull() && !sentence.path("end_time").isMissingNode()) output.put("endMs", sentence.path("end_time").asLong());
                        output.put("text", content); events.accept(output); return;
                    }
                    if ("task-finished".equals(type)) { events.accept(Map.of("type", "finished")); close(); return; }
                    if ("task-failed".equals(type)) {
                        String message = safe(event.path("header").path("error_message").asText("实时字幕服务返回失败"));
                        events.accept(Map.of("type", "error", "code", "REALTIME_ASR_UPSTREAM_FAILED", "message", message, "recoverable", retries.get() < 3));
                        reconnect("REALTIME_ASR_UPSTREAM_FAILED");
                    }
                } catch (Exception exception) { reconnect("REALTIME_ASR_PROTOCOL_FAILED"); }
            }
            @Override public void onError(WebSocket webSocket, Throwable error) { if (socket == webSocket) reconnect("REALTIME_ASR_CONNECTION_FAILED"); }
            @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (socket == webSocket && !closed && !finishing) reconnect("REALTIME_ASR_CONNECTION_CLOSED");
                return CompletableFuture.completedFuture(null);
            }
        }
    }
    private static String safe(String value) {
        String normalized = value == null ? "实时字幕服务失败" : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(300, normalized.length()));
    }
}
