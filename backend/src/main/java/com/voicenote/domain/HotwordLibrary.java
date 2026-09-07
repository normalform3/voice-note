package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hotword_libraries")
public class HotwordLibrary {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, columnDefinition = "json") private String entries;
    @Column(name = "content_hash", nullable = false, columnDefinition = "CHAR(64)") private String contentHash;
    @Column(nullable = false) private int revision;
    @Column(name = "provider_prefix", nullable = false, length = 10) private String providerPrefix;
    @Column(name = "provider_vocabulary_id") private String providerVocabularyId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private HotwordLibraryStatus status;
    @Column(name = "error_code", length = 128) private String errorCode;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "provider_synced_at") private Instant providerSyncedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected HotwordLibrary() { }

    public HotwordLibrary(String ownerId, String displayName, String entries, String contentHash) {
        this.id = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.displayName = displayName;
        this.entries = entries;
        this.contentHash = contentHash;
        this.providerPrefix = "vn" + id.replace("-", "").substring(0, 8);
        this.status = HotwordLibraryStatus.SYNCING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getDisplayName() { return displayName; }
    public String getEntries() { return entries; }
    public String getContentHash() { return contentHash; }
    public int getRevision() { return revision; }
    public String getProviderPrefix() { return providerPrefix; }
    public String getProviderVocabularyId() { return providerVocabularyId; }
    public HotwordLibraryStatus getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getProviderSyncedAt() { return providerSyncedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void rename(String displayName) { this.displayName = displayName; this.updatedAt = Instant.now(); }
    public void replace(String displayName, String entries, String contentHash) {
        this.displayName = displayName; this.entries = entries; this.contentHash = contentHash;
        this.status = HotwordLibraryStatus.SYNCING; clearError(); this.updatedAt = Instant.now();
    }
    public void ready(String vocabularyId) {
        this.providerVocabularyId = vocabularyId; this.status = HotwordLibraryStatus.READY; this.revision += 1;
        this.providerSyncedAt = Instant.now(); clearError(); this.updatedAt = providerSyncedAt;
    }
    public void syncFailed(String code, String message) {
        this.status = HotwordLibraryStatus.SYNC_FAILED; this.errorCode = code; this.errorMessage = message; this.updatedAt = Instant.now();
    }
    public void beginSync() { this.status = HotwordLibraryStatus.SYNCING; clearError(); this.updatedAt = Instant.now(); }
    public void beginDelete() { this.status = HotwordLibraryStatus.DELETING; clearError(); this.updatedAt = Instant.now(); }
    public void deleteFailed(String code, String message) {
        this.status = HotwordLibraryStatus.DELETE_FAILED; this.errorCode = code; this.errorMessage = message; this.updatedAt = Instant.now();
    }
    public void deleted() { this.status = HotwordLibraryStatus.DELETED; clearError(); this.updatedAt = Instant.now(); }
    private void clearError() { this.errorCode = null; this.errorMessage = null; }
}
