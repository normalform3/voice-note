package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "realtime_recording_parts", uniqueConstraints = @UniqueConstraint(name = "uk_realtime_recording_part", columnNames = {"session_id", "part_number"}))
public class RealtimeRecordingPart {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "session_id", nullable = false, columnDefinition = "CHAR(36)") private String sessionId;
    @Column(name = "part_number", nullable = false) private int partNumber;
    @Column(name = "object_key", nullable = false, length = 1024) private String objectKey;
    @Column(name = "content_length", nullable = false) private long contentLength;
    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)") private String sha256;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected RealtimeRecordingPart() { }
    public RealtimeRecordingPart(String sessionId, int partNumber, String objectKey, long contentLength, String sha256) {
        this.id = UUID.randomUUID().toString(); this.sessionId = sessionId; this.partNumber = partNumber;
        this.objectKey = objectKey; this.contentLength = contentLength; this.sha256 = sha256; this.receivedAt = Instant.now();
    }
    public String getSessionId() { return sessionId; }
    public int getPartNumber() { return partNumber; }
    public String getObjectKey() { return objectKey; }
    public long getContentLength() { return contentLength; }
    public String getSha256() { return sha256; }
}
