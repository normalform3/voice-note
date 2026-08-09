package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_versions")
public class SkillVersion {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "skill_definition_id", nullable = false, length = 128) private String skillDefinitionId;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "version_name", nullable = false, length = 64) private String versionName;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String instructions;
    @Column(name = "allowed_tools", nullable = false, columnDefinition = "json") private String allowedTools;
    @Column(name = "output_blocks", nullable = false, columnDefinition = "json") private String outputBlocks;
    @Column(name = "should_trigger", nullable = false, columnDefinition = "json") private String shouldTrigger;
    @Column(name = "should_not_trigger", nullable = false, columnDefinition = "json") private String shouldNotTrigger;
    @Column(name = "default_prompt", length = 1000) private String defaultPrompt;
    @Column(name = "content_hash", nullable = false, columnDefinition = "CHAR(64)") private String contentHash;
    @Column(name = "trigger_preview_passed", nullable = false) private boolean triggerPreviewPassed;
    @Column(name = "trigger_preview_hash", columnDefinition = "CHAR(64)") private String triggerPreviewHash;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SkillVersion() { }
    public SkillVersion(String skillDefinitionId, int versionNumber, String versionName, String instructions, String allowedTools,
                        String outputBlocks, String shouldTrigger, String shouldNotTrigger, String defaultPrompt, String contentHash) {
        this.id = UUID.randomUUID().toString(); this.skillDefinitionId = skillDefinitionId; this.versionNumber = versionNumber;
        this.versionName = versionName; this.instructions = instructions; this.allowedTools = allowedTools; this.outputBlocks = outputBlocks;
        this.shouldTrigger = shouldTrigger; this.shouldNotTrigger = shouldNotTrigger; this.defaultPrompt = defaultPrompt;
        this.contentHash = contentHash; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getSkillDefinitionId() { return skillDefinitionId; }
    public int getVersionNumber() { return versionNumber; }
    public String getVersionName() { return versionName; }
    public String getInstructions() { return instructions; }
    public String getAllowedTools() { return allowedTools; }
    public String getOutputBlocks() { return outputBlocks; }
    public String getShouldTrigger() { return shouldTrigger; }
    public String getShouldNotTrigger() { return shouldNotTrigger; }
    public String getDefaultPrompt() { return defaultPrompt; }
    public String getContentHash() { return contentHash; }
    public boolean isTriggerPreviewPassed() { return triggerPreviewPassed; }
    public String getTriggerPreviewHash() { return triggerPreviewHash; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isPublished() { return publishedAt != null; }
    public void updateDraft(String instructions, String allowedTools, String outputBlocks, String shouldTrigger,
                            String shouldNotTrigger, String defaultPrompt, String contentHash) {
        if (isPublished()) throw new IllegalStateException("Published Skill versions are immutable");
        boolean contentChanged = !java.util.Objects.equals(this.contentHash, contentHash);
        this.instructions = instructions; this.allowedTools = allowedTools; this.outputBlocks = outputBlocks;
        this.shouldTrigger = shouldTrigger; this.shouldNotTrigger = shouldNotTrigger; this.defaultPrompt = defaultPrompt;
        this.contentHash = contentHash;
        if (contentChanged) { this.triggerPreviewPassed = false; this.triggerPreviewHash = null; }
        this.updatedAt = Instant.now();
    }
    public void publish() { if (publishedAt == null) publishedAt = Instant.now(); updatedAt = Instant.now(); }
    public void markTriggerPreview(boolean passed, String previewHash) { this.triggerPreviewPassed = passed; this.triggerPreviewHash = previewHash; this.updatedAt = Instant.now(); }
}
