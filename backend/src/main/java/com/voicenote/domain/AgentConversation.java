package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_conversations")
public class AgentConversation {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(nullable = false, length = 160) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AgentConversationStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "scope_type", nullable = false) private AgentScopeType scopeType;
    @Column(name = "time_zone", nullable = false, length = 64) private String timeZone;
    @Column(name = "skill_id", nullable = false, length = 128) private String skillId;
    @Column(name = "skill_version", nullable = false, length = 64) private String skillVersion;
    @Column(name = "skill_version_id", columnDefinition = "CHAR(36)") private String skillVersionId;
    @Column(name = "skill_snapshot", columnDefinition = "json") private String skillSnapshot;
    @Column(name = "skill_hash", columnDefinition = "CHAR(64)") private String skillHash;
    @Column(name = "memory_enabled", nullable = false) private boolean memoryEnabled;
    @Column(name = "rolling_summary", columnDefinition = "MEDIUMTEXT") private String rollingSummary;
    @Column(name = "summary_through_turn", nullable = false) private int summaryThroughTurn;
    @Enumerated(EnumType.STRING) @Column(name = "summary_status", nullable = false) private ConversationSummaryStatus summaryStatus;
    @Column(name = "summary_attempts", nullable = false) private int summaryAttempts;
    @Column(name = "summary_input_hash", columnDefinition = "CHAR(64)") private String summaryInputHash;
    @Column(name = "summary_prompt_version", length = 64) private String summaryPromptVersion;
    @Column(name = "summary_model_id", length = 128) private String summaryModelId;
    @Column(name = "summary_duration_ms") private Long summaryDurationMs;
    @Column(name = "summary_failure_code", length = 128) private String summaryFailureCode;
    @Column(name = "summary_failure_message") private String summaryFailureMessage;
    @Column(name = "next_turn_index", nullable = false) private int nextTurnIndex;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected AgentConversation() { }
    public AgentConversation(String ownerId, String title, AgentScopeType scopeType, String timeZone,
                             String skillId, String skillVersion, String skillVersionId,
                             String skillSnapshot, String skillHash, boolean memoryEnabled) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.title = title;
        this.scopeType = scopeType; this.timeZone = timeZone; this.skillId = skillId; this.skillVersion = skillVersion;
        this.skillVersionId = skillVersionId; this.skillSnapshot = skillSnapshot; this.skillHash = skillHash;
        this.memoryEnabled = memoryEnabled; this.status = AgentConversationStatus.ACTIVE;
        this.summaryThroughTurn = -1; this.summaryStatus = ConversationSummaryStatus.IDLE;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTitle() { return title; }
    public AgentConversationStatus getStatus() { return status; }
    public AgentScopeType getScopeType() { return scopeType; }
    public String getTimeZone() { return timeZone; }
    public String getSkillId() { return skillId; }
    public String getSkillVersion() { return skillVersion; }
    public String getSkillVersionId() { return skillVersionId; }
    public String getSkillSnapshot() { return skillSnapshot; }
    public String getSkillHash() { return skillHash; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public String getRollingSummary() { return rollingSummary; }
    public int getSummaryThroughTurn() { return summaryThroughTurn; }
    public ConversationSummaryStatus getSummaryStatus() { return summaryStatus; }
    public int getSummaryAttempts() { return summaryAttempts; }
    public String getSummaryFailureMessage() { return summaryFailureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int allocateTurn() { updatedAt = Instant.now(); return nextTurnIndex++; }
    public void update(String title, AgentConversationStatus status, Boolean memoryEnabled) {
        if (title != null && !title.isBlank()) this.title = title.trim();
        if (status != null) this.status = status;
        if (memoryEnabled != null) this.memoryEnabled = memoryEnabled;
        updatedAt = Instant.now();
    }
    public void useFirstQuestionAsTitle(String question) {
        if (!"新会话".equals(title)) return;
        String normalized = question.replaceAll("[\\r\\n]+", " ").trim();
        title = normalized.substring(0, Math.min(60, normalized.length())); updatedAt = Instant.now();
    }
    public void freezeSkill(String id, String skillVersion, String skillVersionId, String snapshot, String hash) {
        if (!"pending".equals(this.skillVersion)) return;
        this.skillId = id; this.skillVersion = skillVersion; this.skillVersionId = skillVersionId;
        this.skillSnapshot = snapshot; this.skillHash = hash; this.updatedAt = Instant.now();
    }
    public void queueSummary(String inputHash) { queueSummary(inputHash, null); }
    public void queueSummary(String inputHash, String promptVersion) {
        if (summaryStatus == ConversationSummaryStatus.RUNNING || summaryStatus == ConversationSummaryStatus.QUEUED) return;
        summaryStatus = ConversationSummaryStatus.QUEUED; summaryInputHash = inputHash; summaryPromptVersion = promptVersion;
        summaryFailureMessage = null; updatedAt = Instant.now();
    }
    public boolean beginSummary() {
        if (summaryStatus != ConversationSummaryStatus.QUEUED) return false;
        summaryStatus = ConversationSummaryStatus.RUNNING; summaryAttempts++; updatedAt = Instant.now(); return true;
    }
    public void completeSummary(String summary, int throughTurn) { completeSummary(summary, throughTurn, null, null); }
    public void completeSummary(String summary, int throughTurn, String modelId, Long durationMs) {
        rollingSummary = summary; summaryThroughTurn = Math.max(summaryThroughTurn, throughTurn);
        summaryStatus = ConversationSummaryStatus.IDLE; summaryModelId = modelId; summaryDurationMs = durationMs;
        summaryFailureCode = null; summaryFailureMessage = null; updatedAt = Instant.now();
    }
    public void failSummary(String message, boolean retry) {
        failSummary(null, message, null, null, retry);
    }
    public void failSummary(String code, String message, String modelId, Long durationMs, boolean retry) {
        summaryStatus = retry ? ConversationSummaryStatus.QUEUED : ConversationSummaryStatus.FAILED;
        summaryFailureCode = code; summaryFailureMessage = message; summaryModelId = modelId;
        summaryDurationMs = durationMs; updatedAt = Instant.now();
    }
}
