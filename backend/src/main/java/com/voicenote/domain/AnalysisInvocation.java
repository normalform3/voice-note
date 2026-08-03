package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis_invocations", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_invocation_stage", columnNames = {"analysis_run_id", "stage_name", "chunk_index"}))
public class AnalysisInvocation {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "analysis_run_id", nullable = false, columnDefinition = "CHAR(36)") private String analysisRunId;
    @Column(name = "stage_name", nullable = false) private String stageName;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "request_hash", nullable = false, columnDefinition = "CHAR(64)") private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private InvocationStatus status;
    @Column(name = "response_document", columnDefinition = "json") private String responseDocument;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected AnalysisInvocation() { }
    public AnalysisInvocation(String runId, String stageName, int chunkIndex, String requestHash) {
        this.id = UUID.randomUUID().toString(); this.analysisRunId = runId; this.stageName = stageName; this.chunkIndex = chunkIndex; this.requestHash = requestHash;
        this.status = InvocationStatus.READY; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public InvocationStatus getStatus() { return status; }
    public String getResponseDocument() { return responseDocument; }
    public void markInFlight() { status = InvocationStatus.IN_FLIGHT; updatedAt = Instant.now(); }
    public void markSucceeded(String response) { status = InvocationStatus.SUCCEEDED; responseDocument = response; updatedAt = Instant.now(); }
    public void markUnknown() { status = InvocationStatus.UNKNOWN; updatedAt = Instant.now(); }
}
