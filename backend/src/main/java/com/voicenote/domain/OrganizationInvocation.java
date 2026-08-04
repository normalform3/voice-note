package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_invocations", uniqueConstraints = @UniqueConstraint(name = "uk_organization_invocation_stage", columnNames = {"organized_document_id", "stage_name"}))
public class OrganizationInvocation {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "organized_document_id", nullable = false, columnDefinition = "CHAR(36)") private String organizedDocumentId;
    @Column(name = "stage_name", nullable = false) private String stageName;
    @Column(name = "request_hash", nullable = false, columnDefinition = "CHAR(64)") private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private InvocationStatus status;
    @Column(name = "response_document", columnDefinition = "json") private String responseDocument;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected OrganizationInvocation() { }
    public OrganizationInvocation(String documentId, String stageName, String requestHash) {
        this.id = UUID.randomUUID().toString(); this.organizedDocumentId = documentId; this.stageName = stageName; this.requestHash = requestHash;
        this.status = InvocationStatus.READY; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public InvocationStatus getStatus() { return status; }
    public String getResponseDocument() { return responseDocument; }
    public void start() { status = InvocationStatus.IN_FLIGHT; updatedAt = Instant.now(); }
    public void succeed(String response) { status = InvocationStatus.SUCCEEDED; responseDocument = response; updatedAt = Instant.now(); }
    public void unknown() { status = InvocationStatus.UNKNOWN; updatedAt = Instant.now(); }
}
