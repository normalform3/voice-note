package com.voicenote.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.RealtimeAsrGateway;
import com.voicenote.service.RealtimeRecordingService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeRecordingWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    public static final String PROTOCOL = "voicenote.realtime.v1";
    private static final int MAX_PCM_FRAME_BYTES = 64 * 1024;
    private final RealtimeRecordingService recordings;
    private final RealtimeAsrGateway gateway;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ConcurrentHashMap<String, ActiveConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeSessions = new ConcurrentHashMap<>();
    public RealtimeRecordingWebSocketHandler(RealtimeRecordingService recordings, RealtimeAsrGateway gateway, ObjectMapper mapper, AppProperties properties) {
        this.recordings = recordings; this.gateway = gateway; this.mapper = mapper; this.properties = properties;
    }
    @Override public List<String> getSubProtocols() { return List.of(PROTOCOL); }

    @Override public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        String recordingSessionId = (String) raw.getAttributes().get("recordingSessionId");
        String ownerId = (String) raw.getAttributes().get("ownerId");
        if (recordingSessionId == null || ownerId == null || activeSessions.putIfAbsent(recordingSessionId, raw.getId()) != null) {
            raw.close(new CloseStatus(1008, "Recording session already has a realtime connection")); return;
        }
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 256 * 1024);
        var recording = recordings.owned(ownerId, recordingSessionId);
        RealtimeAsrGateway.Connection upstream = gateway.open(recording, recordings.languages(recording), event -> send(session, event));
        connections.put(raw.getId(), new ActiveConnection(recordingSessionId, session, upstream));
    }

    @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ActiveConnection active = connections.get(session.getId()); if (active == null) return;
        if (message.getPayloadLength() <= 0 || message.getPayloadLength() > MAX_PCM_FRAME_BYTES) {
            send(active.session(), Map.of("type", "error", "code", "INVALID_PCM_FRAME", "message", "实时音频帧大小无效", "recoverable", false));
            closeQuietly(session, new CloseStatus(1008, "Invalid PCM frame")); return;
        }
        active.upstream().sendAudio(message.getPayload());
        if (active.upstream().durationMs() > properties.getRealtimeAsr().getMaxDurationSeconds() * 1000L) {
            send(active.session(), Map.of("type", "error", "code", "RECORDING_DURATION_EXCEEDED", "message", "实时录音已达到两小时上限", "recoverable", false));
            active.upstream().finish();
        }
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ActiveConnection active = connections.get(session.getId()); if (active == null) return;
        try {
            if (!"finish".equals(mapper.readTree(message.getPayload()).path("type").asText())) throw new IllegalArgumentException();
            active.upstream().finish();
        } catch (RuntimeException exception) { session.close(new CloseStatus(1008, "Unsupported realtime recording command")); }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ActiveConnection active = connections.remove(session.getId());
        if (active != null) { activeSessions.remove(active.recordingSessionId(), session.getId()); active.upstream().close(); }
    }
    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception { session.close(CloseStatus.SERVER_ERROR); }
    private void send(WebSocketSession session, Object event) {
        if (!session.isOpen()) return;
        try { session.sendMessage(new TextMessage(mapper.writeValueAsString(event))); }
        catch (IOException ignored) { }
    }
    private static void closeQuietly(WebSocketSession session, CloseStatus status) { try { session.close(status); } catch (IOException ignored) { } }
    private record ActiveConnection(String recordingSessionId, WebSocketSession session, RealtimeAsrGateway.Connection upstream) { }
}
