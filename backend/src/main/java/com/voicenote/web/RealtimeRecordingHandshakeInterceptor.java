package com.voicenote.web;

import com.voicenote.domain.RealtimeRecordingStatus;
import com.voicenote.security.RealtimeRecordingTicketService;
import com.voicenote.service.RealtimeRecordingService;
import org.springframework.http.*;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Component
public class RealtimeRecordingHandshakeInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RealtimeRecordingHandshakeInterceptor.class);
    static final String TICKET_PREFIX = "voicenote.ticket.";
    private final RealtimeRecordingTicketService tickets;
    private final RealtimeRecordingService recordings;
    public RealtimeRecordingHandshakeInterceptor(RealtimeRecordingTicketService tickets, RealtimeRecordingService recordings) {
        this.tickets = tickets; this.recordings = recordings;
    }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String protocols = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        String raw = protocols == null ? null : java.util.Arrays.stream(protocols.split(","))
                .map(String::trim).filter(value -> value.startsWith(TICKET_PREFIX)).findFirst().orElse(null);
        if (raw == null) return reject(response, "ticket_missing");
        RealtimeRecordingTicketService.TicketClaims claims;
        try {
            claims = tickets.parse(raw.substring(TICKET_PREFIX.length()));
        } catch (RuntimeException exception) {
            return reject(response, "ticket_invalid");
        }
        com.voicenote.domain.RealtimeRecordingSession session;
        try { session = recordings.owned(claims.ownerId(), claims.sessionId()); }
        catch (RuntimeException exception) { return reject(response, "session_not_owned"); }
        if (session.getStatus() != RealtimeRecordingStatus.RECORDING) return reject(response, "session_not_recording");
        attributes.put("ownerId", claims.ownerId()); attributes.put("recordingSessionId", claims.sessionId());
        return true;
    }
    private static boolean reject(ServerHttpResponse response, String reason) {
        log.warn("Realtime recording WebSocket handshake rejected: {}", reason);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) { }
}
