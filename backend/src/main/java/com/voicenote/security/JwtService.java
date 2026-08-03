package com.voicenote.security;

import com.voicenote.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final AppProperties properties;
    public JwtService(AppProperties properties) { this.properties = properties; }

    public String issue(String userId, String account) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId).claim("account", account).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getSecurity().getTokenTtlHours() * 3600)))
                .signWith(key()).compact();
    }

    public Claims parse(String token) { return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload(); }
    private SecretKey key() {
        byte[] bytes = properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        return Keys.hmacShaKeyFor(bytes);
    }
}
