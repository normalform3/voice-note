package com.voicenote.domain;

import com.voicenote.agent.AgentPhase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_checkpoints", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_checkpoint_sequence", columnNames = {"knowledge_run_id", "checkpoint_sequence"}))
public class AgentCheckpoint {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Column(name = "checkpoint_sequence", nullable = false) private int checkpointSequence;
    @Column(name = "state_schema_version", nullable = false) private int stateSchemaVersion;
    @Column(name = "runtime_version", nullable = false, length = 64) private String runtimeVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AgentPhase phase;
    @Column(name = "step_id", columnDefinition = "CHAR(36)") private String stepId;
    @Column(name = "state_document", nullable = false, columnDefinition = "json") private String stateDocument;
    @Column(name = "state_hash", nullable = false, columnDefinition = "CHAR(64)") private String stateHash;
    @Column(nullable = false) private boolean replayable;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AgentCheckpoint() { }

    public AgentCheckpoint(String runId, int sequence, int schemaVersion, String runtimeVersion, AgentPhase phase,
                           String stepId, String stateDocument, String stateHash, boolean replayable) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.checkpointSequence = sequence;
        this.stateSchemaVersion = schemaVersion; this.runtimeVersion = runtimeVersion; this.phase = phase;
        this.stepId = stepId; this.stateDocument = stateDocument; this.stateHash = stateHash;
        this.replayable = replayable; this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getKnowledgeRunId() { return knowledgeRunId; }
    public int getCheckpointSequence() { return checkpointSequence; }
    public int getStateSchemaVersion() { return stateSchemaVersion; }
    public String getRuntimeVersion() { return runtimeVersion; }
    public AgentPhase getPhase() { return phase; }
    public String getStepId() { return stepId; }
    public String getStateDocument() { return stateDocument; }
    public String getStateHash() { return stateHash; }
    public boolean isReplayable() { return replayable; }
    public Instant getCreatedAt() { return createdAt; }
}
