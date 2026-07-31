package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_invocations", uniqueConstraints = @UniqueConstraint(name = "uk_invocation_attempt_type", columnNames = {"task_attempt_id", "invocation_type"}))
public class ProviderInvocation {
    @Id private String id;
    @Column(name = "task_attempt_id", nullable = false) private String taskAttemptId;
    @Column(name = "invocation_type", nullable = false) private String invocationType;
    @Column(name = "request_hash", nullable = false) private String requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private InvocationStatus status;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "response_snapshot", columnDefinition = "json") private String responseSnapshot;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected ProviderInvocation() { }
    public ProviderInvocation(String taskAttemptId, String invocationType, String requestHash) {
        this.id = UUID.randomUUID().toString(); this.taskAttemptId = taskAttemptId; this.invocationType = invocationType; this.requestHash = requestHash;
        this.status = InvocationStatus.READY; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public InvocationStatus getStatus() { return status; }
    public void markInFlight() { status = InvocationStatus.IN_FLIGHT; leaseUntil = Instant.now().plusSeconds(90); updatedAt = Instant.now(); }
    public void markSucceeded(String response) { status = InvocationStatus.SUCCEEDED; responseSnapshot = response; leaseUntil = null; updatedAt = Instant.now(); }
    public void markUnknown() { status = InvocationStatus.UNKNOWN; leaseUntil = null; updatedAt = Instant.now(); }
}
