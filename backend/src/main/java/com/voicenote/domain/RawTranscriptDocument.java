package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable ASR output snapshot; segments remain the queryable source of truth. */
@Entity
@Table(name = "raw_transcript_documents", uniqueConstraints = @UniqueConstraint(name = "uk_raw_transcript_source", columnNames = {"owner_id", "transcription_task_id", "transcript_version"}))
public class RawTranscriptDocument {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "provider_task_id", nullable = false) private String providerTaskId;
    @Column(name = "result_object_key", nullable = false) private String resultObjectKey;
    @Column(name = "result_sha256", nullable = false, columnDefinition = "CHAR(64)") private String resultSha256;
    @Column(name = "content_text", nullable = false, columnDefinition = "MEDIUMTEXT") private String contentText;
    @Column(name = "segment_count", nullable = false) private int segmentCount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected RawTranscriptDocument() { }
    public RawTranscriptDocument(String ownerId, String taskId, int version, String providerTaskId, String resultObjectKey,
                                 String resultSha256, String contentText, int segmentCount) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.transcriptionTaskId = taskId; this.transcriptVersion = version;
        this.providerTaskId = providerTaskId; this.resultObjectKey = resultObjectKey; this.resultSha256 = resultSha256;
        this.contentText = contentText; this.segmentCount = segmentCount; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getResultObjectKey() { return resultObjectKey; }
    public String getContentText() { return contentText; }
}
