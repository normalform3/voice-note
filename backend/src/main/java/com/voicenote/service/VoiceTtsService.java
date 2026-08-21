package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentMetrics;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.ProviderException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class VoiceTtsService {
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;
    private final HttpClient http;

    public VoiceTtsService(AppProperties properties, ObjectMapper mapper, AgentMetrics metrics) {
        this.properties = properties; this.mapper = mapper; this.metrics = metrics;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        AppProperties.Tts tts = properties.getTts();
        String url = tts.getWsUrl();
        if (!tts.isEnabled() || !properties.getDashscope().isEnabled()
                || blank(properties.getDashscope().getApiKey()) || blank(tts.getModel()) || blank(tts.getVoice()) || blank(url)) return false;
        try { return "wss".equalsIgnoreCase(URI.create(url.trim()).getScheme()); }
        catch (IllegalArgumentException exception) { return false; }
    }

    public void stream(String utteranceId, String text, OutputStream output) {
        if (!isEnabled()) throw new IllegalStateException("TTS is disabled or incompletely configured");
        if (text == null || text.isBlank() || text.length() > 500) throw new IllegalArgumentException("TTS text must contain 1 to 500 characters");
        long started = System.nanoTime();
        TtsListener listener = new TtsListener(utteranceId, text, output, started);
        WebSocket socket = null;
        try {
            socket = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + properties.getDashscope().getApiKey())
                    .buildAsync(ttsUri(), listener).join();
            listener.attach(socket);
            boolean completed = listener.finished.await(Math.max(15, properties.getAgent().getTimeoutSeconds()), TimeUnit.SECONDS);
            if (!completed) throw new IOException("DashScope TTS timed out");
            Throwable failure = listener.failure.get();
            if (failure != null) throw failure instanceof IOException io ? io : new IOException(failure);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw failed(exception);
        } catch (Exception exception) {
            if (socket != null) socket.abort();
            throw failed(exception);
        }
    }

    private URI ttsUri() {
        String base = properties.getTts().getWsUrl().trim();
        String separator = base.contains("?") ? "&" : "?";
        return URI.create(base + separator + "model=" + URLEncoder.encode(properties.getTts().getModel(), StandardCharsets.UTF_8));
    }

    private ProviderException failed(Exception exception) {
        return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "TTS_UPSTREAM_FAILED",
                "DashScope TTS failed: " + safe(exception.getMessage()));
    }

    private final class TtsListener implements WebSocket.Listener {
        private final String utteranceId;
        private final String text;
        private final OutputStream output;
        private final long started;
        private final StringBuilder message = new StringBuilder();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean firstAudio = new AtomicBoolean();
        private volatile WebSocket socket;

        private TtsListener(String utteranceId, String text, OutputStream output, long started) {
            this.utteranceId = utteranceId; this.text = text; this.output = output; this.started = started;
        }
        private void attach(WebSocket value) { this.socket = value; }
        @Override public void onOpen(WebSocket webSocket) { attach(webSocket); webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            message.append(data);
            if (last) {
                String value = message.toString(); message.setLength(0);
                try { handle(value); } catch (Exception exception) { fail(exception); }
            }
            webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        private void handle(String value) throws Exception {
            JsonNode event = mapper.readTree(value);
            String type = event.path("type").asText();
            if ("session.created".equals(type)) {
                send(Map.of("event_id", eventId(), "type", "session.update", "session", Map.of(
                        "voice", properties.getTts().getVoice(), "mode", "commit", "language_type", "Chinese",
                        "response_format", "pcm", "sample_rate", 24000)));
            } else if ("session.updated".equals(type)) {
                send(Map.of("event_id", eventId(), "type", "input_text_buffer.append", "text", text));
                send(Map.of("event_id", eventId(), "type", "input_text_buffer.commit"));
            } else if ("response.audio.delta".equals(type)) {
                byte[] pcm = Base64.getDecoder().decode(event.path("delta").asText());
                output.write(pcm); output.flush();
                if (firstAudio.compareAndSet(false, true)) metrics.liveLatency("first_audio_chunk", Duration.ofNanos(System.nanoTime() - started));
            } else if ("response.done".equals(type)) {
                send(Map.of("event_id", eventId(), "type", "session.finish"));
            } else if ("session.finished".equals(type)) {
                finished.countDown();
            } else if ("error".equals(type)) {
                throw new IOException("DashScope TTS returned an error");
            }
        }
        private void send(Object value) throws Exception {
            WebSocket target = socket;
            if (target == null) throw new IOException("TTS WebSocket is not connected");
            target.sendText(mapper.writeValueAsString(value), true).whenComplete((ignored, error) -> { if (error != null) fail(error); });
        }
        private String eventId() { return utteranceId + "-" + UUID.randomUUID(); }
        private void fail(Throwable value) {
            if (failure.compareAndSet(null, value)) { if (socket != null) socket.abort(); finished.countDown(); }
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { fail(error); }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (finished.getCount() > 0 && failure.get() == null) failure.set(new IOException("TTS WebSocket closed before completion"));
            finished.countDown(); return CompletableFuture.completedFuture(null);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) {
        String normalized = value == null ? "unknown error" : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 300));
    }
}
