package com.voicenote.domain;

import com.voicenote.agent.AgentState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "knowledge_runs")
public class KnowledgeRun {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "conversation_id", columnDefinition = "CHAR(36)") private String conversationId;
    @Column(name = "conversation_turn_index") private Integer conversationTurnIndex;
    @Column(name = "memory_enabled", nullable = false) private boolean memoryEnabled;
    @Column(nullable = false, columnDefinition = "TEXT") private String question;
    @Enumerated(EnumType.STRING) @Column(name = "scope_type", nullable = false) private AgentScopeType scopeType;
    @Column(name = "time_zone", nullable = false) private String timeZone;
    @Column(name = "skill_id", nullable = false) private String skillId;
    @Column(name = "skill_version", nullable = false) private String skillVersion;
    @Column(name = "skill_version_id", columnDefinition = "CHAR(36)") private String skillVersionId;
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
    @Column(name = "current_checkpoint_id", columnDefinition = "CHAR(36)") private String currentCheckpointId;
    @Column(name = "parent_run_id", columnDefinition = "CHAR(36)") private String parentRunId;
    @Column(name = "root_run_id", columnDefinition = "CHAR(36)") private String rootRunId;
    @Column(name = "replay_from_checkpoint_id", columnDefinition = "CHAR(36)") private String replayFromCheckpointId;
    @Column(name = "runtime_version", nullable = false, length = 64) private String runtimeVersion;
    @Column(name = "execution_epoch", nullable = false) private long executionEpoch;
    @Column(name = "recovery_count", nullable = false) private int recoveryCount;
    @Column(name = "next_step_index", nullable = false) private int nextStepIndex;
    @Column(name = "next_checkpoint_sequence", nullable = false) private int nextCheckpointSequence;
    @Column(name = "max_active_duration_ms", nullable = false) private long maxActiveDurationMs;
    @Column(name = "active_duration_ms", nullable = false) private long activeDurationMs;
    @Column(name = "result_document", columnDefinition = "json") private String resultDocument;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "failure_code") private String failureCode;
    @Column(name = "failure_stage") private String failureStage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeRun() { }
    public KnowledgeRun(String ownerId, String question, String modelId, int maxToolCalls) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.question = question; this.modelId = modelId;
        this.scopeType = AgentScopeType.ALL_DOCUMENTS; this.timeZone = "Asia/Shanghai"; this.skillId = "knowledge-qa"; this.skillVersion = "legacy-v1";
        this.maxModelCalls = 7; this.maxAgentTurns = 6; this.maxToolCalls = maxToolCalls;
        this.maxActiveDurationMs = 120_000;
        this.status = KnowledgeRunStatus.PENDING; this.runtimeVersion = "legacy-v1";
        this.createdAt = Instant.now(); this.updatedAt = createdAt; this.rootRunId = id;
    }
    public KnowledgeRun(String ownerId, String question, String modelId, AgentScopeType scopeType, String timeZone,
                        String skillId, String skillVersion, String skillSnapshot, String skillHash,
                        int maxModelCalls, int maxAgentTurns, int maxToolCalls) {
        this(ownerId, question, modelId, scopeType, timeZone, skillId, skillVersion, null, skillSnapshot, skillHash,
                maxModelCalls, maxAgentTurns, maxToolCalls);
    }
    public KnowledgeRun(String ownerId, String question, String modelId, AgentScopeType scopeType, String timeZone,
                        String skillId, String skillVersion, String skillVersionId, String skillSnapshot, String skillHash,
                        int maxModelCalls, int maxAgentTurns, int maxToolCalls) {
        this(ownerId, question, modelId, scopeType, timeZone, skillId, skillVersion, skillVersionId, skillSnapshot,
                skillHash, maxModelCalls, maxAgentTurns, maxToolCalls, 120_000);
    }
    public KnowledgeRun(String ownerId, String question, String modelId, AgentScopeType scopeType, String timeZone,
                        String skillId, String skillVersion, String skillVersionId, String skillSnapshot, String skillHash,
                        int maxModelCalls, int maxAgentTurns, int maxToolCalls, long maxActiveDurationMs) {
        this(ownerId, question, modelId, maxToolCalls);
        this.scopeType = scopeType; this.timeZone = timeZone; this.skillId = skillId; this.skillVersion = skillVersion;
        this.skillVersionId = skillVersionId; this.skillSnapshot = skillSnapshot; this.skillHash = skillHash; this.maxModelCalls = maxModelCalls; this.maxAgentTurns = maxAgentTurns;
        this.maxActiveDurationMs = maxActiveDurationMs;
        this.runtimeVersion = AgentState.CURRENT_RUNTIME_VERSION;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getConversationId() { return conversationId; }
    public Integer getConversationTurnIndex() { return conversationTurnIndex; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public String getQuestion() { return question; }
    public AgentScopeType getScopeType() { return scopeType; }
    public String getTimeZone() { return timeZone; }
    public String getSkillId() { return skillId; }
    public String getSkillVersion() { return skillVersion; }
    public String getSkillVersionId() { return skillVersionId; }
    public String getSkillSnapshot() { return skillSnapshot; }
    public String getSkillHash() { return skillHash; }
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
    public String getFailureCode() { return failureCode; }
    public String getFailureStage() { return failureStage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getStartedAt() { return startedAt; }
    public String getCurrentCheckpointId() { return currentCheckpointId; }
    public String getParentRunId() { return parentRunId; }
    public String getRootRunId() { return rootRunId; }
    public String getReplayFromCheckpointId() { return replayFromCheckpointId; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public long getExecutionEpoch() { return executionEpoch; }
    public int getRecoveryCount() { return recoveryCount; }
    public long getMaxActiveDurationMs() { return maxActiveDurationMs; }
    public long getActiveDurationMs() { return activeDurationMs; }
    public boolean isLegacy() { return "legacy-v1".equals(skillVersion); }
    public boolean isTerminal() { return status == KnowledgeRunStatus.SUCCEEDED || status == KnowledgeRunStatus.FAILED
            || status == KnowledgeRunStatus.BUDGET_EXHAUSTED || status == KnowledgeRunStatus.TIMED_OUT; }
    public void selectSkill(String id, String skillVersion, String skillVersionId, String snapshot, String hash) {
        this.skillId = id; this.skillVersion = skillVersion; this.skillVersionId = skillVersionId; this.skillSnapshot = snapshot; this.skillHash = hash; this.updatedAt = Instant.now();
    }
    public void useConversation(String conversationId, int turnIndex, boolean memoryEnabled) {
        this.conversationId = conversationId; this.conversationTurnIndex = turnIndex;
        this.memoryEnabled = memoryEnabled; this.updatedAt = Instant.now();
    }
    public void queue() { if (status == KnowledgeRunStatus.PENDING) { status = KnowledgeRunStatus.QUEUED; updatedAt = Instant.now(); } }
    public boolean start() {
        boolean recovery = status == KnowledgeRunStatus.RUNNING && leaseUntil != null && leaseUntil.isBefore(Instant.now());
        if (status != KnowledgeRunStatus.QUEUED && !recovery) return false;
        if (recovery) recoveryCount++;
        executionEpoch++;
        status = KnowledgeRunStatus.RUNNING; if (startedAt == null) startedAt = Instant.now(); renewLease(); return true;
    }
    public void renewLease() { leaseUntil = Instant.now().plusSeconds(150); updatedAt = Instant.now(); }
    public boolean consumeModelCall() { if (modelCallsUsed >= maxModelCalls) return false; modelCallsUsed++; renewLease(); return true; }
    public boolean consumeTurn() { if (agentTurnsUsed >= maxAgentTurns) return false; agentTurnsUsed++; renewLease(); return true; }
    public boolean consumeModelTurn() {
        if (modelCallsUsed >= maxModelCalls || agentTurnsUsed >= maxAgentTurns) return false;
        modelCallsUsed++; agentTurnsUsed++; renewLease(); return true;
    }
    public boolean consumeTool() { if (toolCallsUsed >= maxToolCalls) { status = KnowledgeRunStatus.BUDGET_EXHAUSTED; updatedAt = Instant.now(); return false; } toolCallsUsed++; updatedAt = Instant.now(); return true; }
    public boolean consumeAgentTool() {
        if (toolCallsUsed >= maxToolCalls) return false;
        toolCallsUsed++; renewLease(); return true;
    }
    public int allocateStepIndex() { return nextStepIndex++; }
    public int allocateCheckpointSequence() { return nextCheckpointSequence++; }
    public void addActiveDuration(long durationMs) { activeDurationMs += Math.max(0, durationMs); updatedAt = Instant.now(); }
    public void useCheckpoint(String checkpointId, String runtime) { currentCheckpointId = checkpointId; runtimeVersion = runtime; updatedAt = Instant.now(); }
    public void succeed(String result) {
        status = KnowledgeRunStatus.SUCCEEDED; resultDocument = result; failureMessage = null; failureCode = null; failureStage = null;
        leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt;
    }
    public void fail(String message) { fail("AGENT_FAILED", "RUNTIME", message); }
    public void fail(String code, String stage, String message) {
        status = KnowledgeRunStatus.FAILED; failureCode = code; failureStage = stage; failureMessage = message;
        leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt;
    }
    public void budgetExhausted(String message) {
        budgetExhausted("AGENT_BUDGET_EXHAUSTED", "BUDGET", message);
    }
    public void budgetExhausted(String code, String stage, String message) {
        status = KnowledgeRunStatus.BUDGET_EXHAUSTED; failureCode = code; failureStage = stage;
        failureMessage = message; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt;
    }
    public void timedOut(String message) {
        status = KnowledgeRunStatus.TIMED_OUT; failureCode = "AGENT_TIMED_OUT"; failureStage = "RUNTIME";
        failureMessage = message; leaseUntil = null; completedAt = Instant.now(); updatedAt = completedAt;
    }

    public static KnowledgeRun replayOf(KnowledgeRun parent, String checkpointId, AgentState state) {
        String skillVersionId = Objects.equals(parent.skillId, state.skillId())
                && Objects.equals(parent.skillVersion, state.skillVersion()) ? parent.skillVersionId : null;
        KnowledgeRun replay = new KnowledgeRun(parent.ownerId, parent.question, state.modelId(), parent.scopeType, parent.timeZone,
                state.skillId(), state.skillVersion(), skillVersionId, state.skillSnapshot(), state.skillHash(),
                state.maxModelCalls(), state.maxAgentTurns(), state.maxToolCalls(), state.maxActiveDurationMs());
        replay.parentRunId = parent.id;
        replay.rootRunId = parent.rootRunId == null ? parent.id : parent.rootRunId;
        replay.replayFromCheckpointId = checkpointId;
        replay.memoryEnabled = parent.memoryEnabled;
        replay.modelCallsUsed = state.modelCallsUsed();
        replay.agentTurnsUsed = state.agentTurnsUsed();
        replay.toolCallsUsed = state.toolCallsUsed();
        replay.activeDurationMs = state.activeDurationMs();
        return replay;
    }
}
