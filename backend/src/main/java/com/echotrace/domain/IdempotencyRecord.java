package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_owner_operation_key", columnNames = {"owner_id", "operation_name", "idempotency_key"}))
public class IdempotencyRecord {
    @Id private String id;
    @Column(name = "owner_id", nullable = false) private String ownerId;
    @Column(name = "operation_name", nullable = false) private String operationName;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false) private String requestHash;
    @Column(name = "resource_id") private String resourceId;
    @Column(name = "response_status") private Integer responseStatus;
    @Column(name = "response_body", columnDefinition = "json") private String responseBody;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    protected IdempotencyRecord() { }
    public IdempotencyRecord(String ownerId, String operationName, String idempotencyKey, String requestHash) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.operationName = operationName; this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash; this.createdAt = Instant.now(); this.expiresAt = createdAt.plusSeconds(24 * 3600);
    }
    public String getId() { return id; }
    public String getRequestHash() { return requestHash; }
    public String getResourceId() { return resourceId; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public void complete(String resourceId, int responseStatus, String responseBody) { this.resourceId = resourceId; this.responseStatus = responseStatus; this.responseBody = responseBody; }
}
