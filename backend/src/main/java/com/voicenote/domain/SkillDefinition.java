package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_definitions")
public class SkillDefinition {
    @Id @Column(length = 128) private String id;
    @Column(name = "owner_id", columnDefinition = "CHAR(36)") private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SkillSource source;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 1000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SkillStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "invocation_policy", nullable = false) private SkillInvocationPolicy invocationPolicy;
    @Column(name = "scene_types", nullable = false, columnDefinition = "json") private String sceneTypes;
    @Column(name = "scope_types", nullable = false, columnDefinition = "json") private String scopeTypes;
    @Column(name = "draft_version_id", columnDefinition = "CHAR(36)") private String draftVersionId;
    @Column(name = "published_version_id", columnDefinition = "CHAR(36)") private String publishedVersionId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SkillDefinition() { }

    public static SkillDefinition builtIn(String id, String displayName, String description, String sceneTypes, String scopeTypes) {
        return new SkillDefinition(id, null, SkillSource.BUILTIN, displayName, description, SkillStatus.PUBLISHED,
                SkillInvocationPolicy.AUTO, sceneTypes, scopeTypes);
    }

    public static SkillDefinition user(String ownerId, String displayName, String description, String sceneTypes, String scopeTypes) {
        return new SkillDefinition(UUID.randomUUID().toString(), ownerId, SkillSource.USER, displayName, description, SkillStatus.DRAFT,
                SkillInvocationPolicy.MANUAL_ONLY, sceneTypes, scopeTypes);
    }

    private SkillDefinition(String id, String ownerId, SkillSource source, String displayName, String description, SkillStatus status,
                            SkillInvocationPolicy invocationPolicy, String sceneTypes, String scopeTypes) {
        this.id = id; this.ownerId = ownerId; this.source = source; this.displayName = displayName; this.description = description;
        this.status = status; this.invocationPolicy = invocationPolicy; this.sceneTypes = sceneTypes; this.scopeTypes = scopeTypes;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public SkillSource getSource() { return source; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public SkillStatus getStatus() { return status; }
    public SkillInvocationPolicy getInvocationPolicy() { return invocationPolicy; }
    public String getSceneTypes() { return sceneTypes; }
    public String getScopeTypes() { return scopeTypes; }
    public String getDraftVersionId() { return draftVersionId; }
    public String getPublishedVersionId() { return publishedVersionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void updateMetadata(String displayName, String description, String sceneTypes, String scopeTypes) {
        this.displayName = displayName; this.description = description; this.sceneTypes = sceneTypes; this.scopeTypes = scopeTypes;
        this.updatedAt = Instant.now();
    }
    public void setDraftVersion(String versionId) { this.draftVersionId = versionId; if (publishedVersionId == null) this.status = SkillStatus.DRAFT; this.updatedAt = Instant.now(); }
    public void publish(String versionId) { this.publishedVersionId = versionId; this.draftVersionId = null; this.status = SkillStatus.PUBLISHED; this.invocationPolicy = SkillInvocationPolicy.MANUAL_ONLY; this.updatedAt = Instant.now(); }
    public void publishBuiltIn(String versionId) { this.publishedVersionId = versionId; this.draftVersionId = null; this.status = SkillStatus.PUBLISHED; this.invocationPolicy = SkillInvocationPolicy.AUTO; this.updatedAt = Instant.now(); }
    public void setInvocationPolicy(SkillInvocationPolicy value) { this.invocationPolicy = value; this.updatedAt = Instant.now(); }
    public void archive() { this.status = SkillStatus.ARCHIVED; this.invocationPolicy = SkillInvocationPolicy.MANUAL_ONLY; this.updatedAt = Instant.now(); }
}
