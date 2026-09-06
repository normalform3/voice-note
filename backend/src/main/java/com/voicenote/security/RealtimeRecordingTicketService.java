package com.voicenote.security;

import com.voicenote.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;

@Service
public class RealtimeRecordingTicketService {
    private static final String PURPOSE = "realtime-recording-asr";
    private final AppProperties properties;
    public RealtimeRecordingTicketService(AppProperties properties) { this.properties = properties; }

    public Ticket issue(String ownerId, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(10, Math.min(30, properties.getRealtimeAsr().getTicketTtlSeconds())));
        String token = Jwts.builder().subject(ownerId).claim("purpose", PURPOSE).claim("sessionId", sessionId)
                .issuedAt(Date.from(now)).expiration(Date.from(expiresAt)).signWith(key()).compact();
        return new Ticket(token, expiresAt);
    }

    public TicketClaims parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
        if (!PURPOSE.equals(claims.get("purpose", String.class))) throw new IllegalArgumentException("Invalid realtime recording ticket purpose");
        String sessionId = claims.get("sessionId", String.class);
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Realtime recording ticket has no session");
        return new TicketClaims(claims.getSubject(), sessionId);
    }

    private SecretKey key() {
        try {
            byte[] base = properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(base); digest.update(":realtime-recording-ticket-v1".getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(digest.digest());
        } catch (Exception exception) { throw new IllegalStateException("Cannot derive realtime recording ticket key", exception); }
    }
    public record Ticket(String value, Instant expiresAt) { }
    public record TicketClaims(String ownerId, String sessionId) { }
}
