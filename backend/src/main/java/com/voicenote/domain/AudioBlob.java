package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audio_blobs", uniqueConstraints = @UniqueConstraint(name = "uk_audio_blobs_owner_hash", columnNames = {"owner_id", "sha256"}))
public class AudioBlob {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)") private String sha256;
    @Column(name = "content_length", nullable = false) private long contentLength;
    @Column(name = "content_type", nullable = false) private String contentType;
    @Column(name = "original_filename", nullable = false) private String originalFilename;
    @Column(name = "object_key", nullable = false) private String objectKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BlobStatus status;
    @Column(name = "write_started_at") private Instant writeStartedAt;
    @Column(name = "failure_reason") private String failureReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected AudioBlob() { }
    public AudioBlob(String ownerId, String sha256, long contentLength, String contentType, String originalFilename) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.sha256 = sha256; this.contentLength = contentLength;
        this.contentType = contentType; this.originalFilename = originalFilename;
        this.objectKey = "owners/" + ownerId + "/audio/" + id + "/source";
        this.status = BlobStatus.UPLOADING; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getSha256() { return sha256; }
    public long getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public String getOriginalFilename() { return originalFilename; }
    public String getObjectKey() { return objectKey; }
    public BlobStatus getStatus() { return status; }
    public Instant getWriteStartedAt() { return writeStartedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getFailureReason() { return failureReason; }
    public boolean claimWrite() { if (status != BlobStatus.UPLOADING) return false; status = BlobStatus.WRITING; writeStartedAt = Instant.now(); return true; }
    public boolean reopenForUpload() { if (status != BlobStatus.FAILED) return false; return reopen(); }
    public boolean reopenStaleWrite(Instant cutoff) { if (status != BlobStatus.WRITING || (writeStartedAt != null && writeStartedAt.isAfter(cutoff))) return false; return reopen(); }
    public void markReady() { status = BlobStatus.READY; completedAt = Instant.now(); failureReason = null; writeStartedAt = null; }
    public void markFailed(String reason) { status = BlobStatus.FAILED; failureReason = reason; writeStartedAt = null; }
    private boolean reopen() { status = BlobStatus.UPLOADING; failureReason = null; return true; }
}
