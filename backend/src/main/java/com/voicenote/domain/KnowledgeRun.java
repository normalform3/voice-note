package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_runs")
public class KnowledgeRun {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(nullable = false, columnDefinition = "TEXT") private String question;
    @Enumerated(EnumType.STRING) @Column(name = "scope_type", nullable = false) private AgentScopeType scopeType;
    @Column(name = "time_zone", nullable = false) private String timeZone;
    @Column(name = "skill_id", nullable = false) private String skillId;
    @Column(name = "skill_version", nullable = false) private String skillVersion;
    @Column(name = "skill_snapshot", columnDefinition = "json") private String skillSnapshot;
    @Column(name = "skill_hash", columnDefinition = "CHAR(64)") private String skillHash;
    @Column(name = "model_id", nullable = false) private String modelId;
    @Column(name = "max_model_calls", nullable = false) private int maxModelCalls;
    @Column(name = "model_calls_used", nullable = false) private int modelCallsUsed;
    @Column(name = "max_agent_turns", nullable = false) private int maxAgentTurns;
    @Column(name = "agent_turns_used", nullable = false) private int agentTurnsUsed;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeRunStatus status;
    @Column(name = "max_tool_calls", nullable = false) private int maxToolCalls;
    @Column(name = "tool_calls_used", nullable = false) private int toolCallsUsed;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "result_document", columnDefinition = "json") private String resultDocument;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeRun() { }
    public KnowledgeRun(String ownerId, String question, String modelId, int maxToolCalls) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.question = question; this.modelId = modelId;
        this.scopeType = AgentScopeType.ALL_DOCUMENTS; this.timeZone = "Asia/Shanghai"; this.skillId = "knowledge-qa"; this.skillVersion = "legacy-v1";
        this.maxModelCalls = 7; this.maxAgentTurns = 6; this.maxToolCalls = maxToolCalls;
        this.status = KnowledgeRunStatus.PENDING; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public KnowledgeRun(String ownerId, String question, String modelId, AgentScopeType scopeType, String timeZone,
                        String skillId, String skillVersion, String skillSnapshot, String skillHash,
                        int maxModelCalls, int maxAgentTurns, int maxToolCalls) {
        this(ownerId, question, modelId, maxToolCalls);
        this.scopeType = scopeType; this.timeZone = timeZone; this.skillId = skillId; this.skillVersion = skillVersion;
        this.skillSnapshot = skillSnapshot; this.skillHash = skillHash; this.maxModelCalls = maxModelCalls; this.maxAgentTurns = maxAgentTurns;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getQuestion() { return question; }
    public AgentScopeType getScopeType() { return scopeType; }
    public String getTimeZone() { return timeZone; }
    public String getSkillId() { return skillId; }
    public String getSkillVersion() { return skillVersion; }
    public String getSkillSnapshot() { return skillSnapshot; }
    public String getModelId() { return modelId; }
    public KnowledgeRunStatus getStatus() { return status; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getToolCallsUsed() { return toolCallsUsed; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public int getModelCallsUsed() { return modelCallsUsed; }
    public int getMaxAgentTurns() { return maxAgentTurns; }
    public int getAgentTurnsUsed() { return agentTurnsUsed; }
    public String getResultDocument() { return resultDocument; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getStartedAt() { return startedAt; }
    public boolean isLegacy() { return "legacy-v1".equals(skillVersion); }
    public void selectSkill(String id, String skillVersion, String snapshot, String hash) {
        this.skillId = id; this.skillVersion = skillVersion; this.skillSnapshot = snapshot; this.skillHash = hash; this.updatedAt = Instant.now();
    }
    public void queue() { if (status == KnowledgeRunStatus.PENDING) { status = KnowledgeRunStatus.QUEUED; updatedAt = Instant.now(); } }
    public boolean start() {
        if (status != KnowledgeRunStatus.QUEUED && !(status == KnowledgeRunStatus.RUNNING && leaseUntil != null && leaseUntil.isBefore(Instant.now()))) return false;
        status = KnowledgeRunStatus.RUNNING; if (startedAt == null) startedAt = Instant.now(); renewLease(); return true;
    }
    public void renewLease() { leaseUntil = Instant.now().plusSeconds(150); updatedAt = Instant.now(); }
    public boolean consumeModelCall() { if (modelCallsUsed >= maxModelCalls) return false; modelCallsUsed++; renewLease(); return true; }
    public boolean consumeTurn() { if (agentTurnsUsed >= maxAgentTurns) return false; agentTurnsUsed++; renewLease(); return true; }
    public boolean consumeTool() { if (toolCallsUsed >= maxToolCalls) { status = KnowledgeRunStatus.BUDGET_EXHAUSTED; updatedAt = Instant.now(); return false; } toolCallsUsed++; updatedAt = Instant.now(); return true; }
    public void succeed(String result) { status = KnowledgeRunStatus.SUCCEEDED; resultDocument = result; failureMessage = null; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void fail(String message) { status = KnowledgeRunStatus.FAILED; failureMessage = message; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void budgetExhausted(String message) { status = KnowledgeRunStatus.BUDGET_EXHAUSTED; failureMessage = message; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void timedOut(String message) { status = KnowledgeRunStatus.TIMED_OUT; failureMessage = message; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt; }
}
