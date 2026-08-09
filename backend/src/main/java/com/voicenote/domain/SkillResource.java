package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_resources")
public class SkillResource {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "skill_version_id", nullable = false, columnDefinition = "CHAR(36)") private String skillVersionId;
    @Column(name = "resource_key", nullable = false, length = 160) private String resourceKey;
    @Enumerated(EnumType.STRING) @Column(name = "resource_type", nullable = false) private SkillResourceType resourceType;
    @Column(nullable = false, length = 160) private String name;
    @Column(nullable = false, length = 500) private String purpose;
    @Column(name = "markdown_content", nullable = false, columnDefinition = "MEDIUMTEXT") private String markdownContent;
    @Column(name = "content_hash", nullable = false, columnDefinition = "CHAR(64)") private String contentHash;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SkillResource() { }
    public SkillResource(String skillVersionId, String resourceKey, SkillResourceType resourceType, String name, String purpose,
                         String markdownContent, String contentHash, int sortOrder) {
        this.id = UUID.randomUUID().toString(); this.skillVersionId = skillVersionId; this.resourceKey = resourceKey;
        this.resourceType = resourceType; this.name = name; this.purpose = purpose; this.markdownContent = markdownContent;
        this.contentHash = contentHash; this.sortOrder = sortOrder; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getSkillVersionId() { return skillVersionId; }
    public String getResourceKey() { return resourceKey; }
    public SkillResourceType getResourceType() { return resourceType; }
    public String getName() { return name; }
    public String getPurpose() { return purpose; }
    public String getMarkdownContent() { return markdownContent; }
    public String getContentHash() { return contentHash; }
    public int getSortOrder() { return sortOrder; }
}
