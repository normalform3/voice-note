package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "speaker_correction_invocations", uniqueConstraints = @UniqueConstraint(name = "uk_speaker_correction_invocation", columnNames = {"run_id", "chunk_index", "attempt_number"}))
public class SpeakerCorrectionInvocation {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "run_id", nullable = false, columnDefinition = "CHAR(36)") private String runId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Column(name = "request_hash", nullable = false, columnDefinition = "CHAR(64)") private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private InvocationStatus status;
    @Column(name = "response_document", columnDefinition = "MEDIUMTEXT") private String responseDocument;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected SpeakerCorrectionInvocation() { }
    public SpeakerCorrectionInvocation(String runId, int chunkIndex, int attemptNumber, String requestHash) {
        this.id = UUID.randomUUID().toString(); this.runId = runId; this.chunkIndex = chunkIndex; this.attemptNumber = attemptNumber;
        this.requestHash = requestHash; this.status = InvocationStatus.READY; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public InvocationStatus getStatus() { return status; }
    public String getResponseDocument() { return responseDocument; }
    public void inFlight() { status = InvocationStatus.IN_FLIGHT; updatedAt = Instant.now(); }
    public void succeeded(String response) { status = InvocationStatus.SUCCEEDED; responseDocument = response; updatedAt = Instant.now(); }
    public void unknown() { status = InvocationStatus.UNKNOWN; updatedAt = Instant.now(); }
}
