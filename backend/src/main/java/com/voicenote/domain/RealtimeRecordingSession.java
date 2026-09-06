package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "realtime_recording_sessions")
public class RealtimeRecordingSession {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private RealtimeRecordingStatus status;
    @Column(name = "content_type", nullable = false, length = 128) private String contentType;
    @Column(name = "original_filename", nullable = false, length = 512) private String originalFilename;
    @Column(name = "sample_rate", nullable = false) private int sampleRate;
    @Column(name = "language_hints", nullable = false, columnDefinition = "json") private String languageHints;
    @Column(name = "asr_config", nullable = false, columnDefinition = "json") private String asrConfig;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "next_part_number", nullable = false) private int nextPartNumber;
    @Column(name = "expected_part_count") private Integer expectedPartCount;
    @Column(name = "total_bytes", nullable = false) private long totalBytes;
    @Column(name = "last_part_at") private Instant lastPartAt;
    @Column(name = "audio_blob_id", columnDefinition = "CHAR(36)") private String audioBlobId;
    @Column(name = "transcription_task_id", columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "failure_code", length = 128) private String failureCode;
    @Column(name = "failure_message", length = 1000) private String failureMessage;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected RealtimeRecordingSession() { }
    public RealtimeRecordingSession(String ownerId, String contentType, String originalFilename, int sampleRate,
                                    String languageHints, String asrConfig, Instant startedAt) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.status = RealtimeRecordingStatus.RECORDING;
        this.contentType = contentType; this.originalFilename = originalFilename; this.sampleRate = sampleRate;
        this.languageHints = languageHints; this.asrConfig = asrConfig; this.startedAt = startedAt;
        this.expiresAt = now.plusSeconds(24 * 3600); this.createdAt = now; this.updatedAt = now;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public RealtimeRecordingStatus getStatus() { return status; }
    public String getContentType() { return contentType; }
    public String getOriginalFilename() { return originalFilename; }
    public int getSampleRate() { return sampleRate; }
    public String getLanguageHints() { return languageHints; }
    public String getAsrConfig() { return asrConfig; }
    public Instant getStartedAt() { return startedAt; }
    public int getNextPartNumber() { return nextPartNumber; }
    public Integer getExpectedPartCount() { return expectedPartCount; }
    public long getTotalBytes() { return totalBytes; }
    public String getAudioBlobId() { return audioBlobId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void partReceived(int partNumber, long length) {
        if (status != RealtimeRecordingStatus.RECORDING || partNumber != nextPartNumber) throw new IllegalStateException("Recording part is out of sequence");
        nextPartNumber += 1; totalBytes += length; lastPartAt = Instant.now(); updatedAt = lastPartAt; expiresAt = lastPartAt.plusSeconds(24 * 3600);
    }
    public void beginFinalizing(int partCount) {
        if (status == RealtimeRecordingStatus.READY) return;
        if (status != RealtimeRecordingStatus.RECORDING && status != RealtimeRecordingStatus.FAILED) throw new IllegalStateException("Recording cannot be finalized");
        status = RealtimeRecordingStatus.FINALIZING; expectedPartCount = partCount; failureCode = null; failureMessage = null; updatedAt = Instant.now();
    }
    public void beginProcessing() {
        if (status != RealtimeRecordingStatus.FINALIZING && status != RealtimeRecordingStatus.FAILED) throw new IllegalStateException("Recording is not ready for processing");
        status = RealtimeRecordingStatus.PROCESSING; updatedAt = Instant.now();
    }
    public void ready(String blobId, String taskId) {
        status = RealtimeRecordingStatus.READY; audioBlobId = blobId; transcriptionTaskId = taskId;
        failureCode = null; failureMessage = null; updatedAt = Instant.now(); expiresAt = updatedAt.plusSeconds(7 * 24 * 3600);
    }
    public void fail(String code, String message) {
        status = RealtimeRecordingStatus.FAILED; failureCode = code; failureMessage = message; updatedAt = Instant.now(); expiresAt = updatedAt.plusSeconds(24 * 3600);
    }
    public void abort() { status = RealtimeRecordingStatus.ABORTED; updatedAt = Instant.now(); expiresAt = updatedAt; }
    public void expire() { status = RealtimeRecordingStatus.EXPIRED; updatedAt = Instant.now(); }
}
