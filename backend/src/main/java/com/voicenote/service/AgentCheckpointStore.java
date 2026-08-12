package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentState;
import com.voicenote.domain.AgentCheckpoint;
import com.voicenote.domain.KnowledgeRun;
import com.voicenote.repository.AgentCheckpointRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentCheckpointStore {
    private final AgentCheckpointRepository checkpoints;
    private final ObjectMapper mapper;

    public AgentCheckpointStore(AgentCheckpointRepository checkpoints, ObjectMapper mapper) {
        this.checkpoints = checkpoints; this.mapper = mapper;
    }

    public AgentCheckpoint save(KnowledgeRun run, AgentState state, String stepId, boolean replayable) {
        try {
            AgentState durable = state.withRuntimeStats(run.getModelCallsUsed(), run.getAgentTurnsUsed(),
                    run.getToolCallsUsed(), run.getActiveDurationMs());
            String document = AgentTraceSanitizer.sanitizeJson(mapper, mapper.writeValueAsString(durable));
            AgentCheckpoint checkpoint = checkpoints.save(new AgentCheckpoint(run.getId(), run.allocateCheckpointSequence(),
                    durable.schemaVersion(), durable.runtimeVersion(), durable.phase(), stepId, document,
                    Hashing.sha256(document), replayable));
            run.useCheckpoint(checkpoint.getId(), durable.runtimeVersion());
            return checkpoint;
        } catch (CheckpointException exception) { throw exception; }
        catch (Exception exception) { throw new CheckpointException("CHECKPOINT_WRITE_FAILED", "Cannot persist Agent checkpoint", exception); }
    }

    public AgentState read(AgentCheckpoint checkpoint) {
        if (!Hashing.sha256(checkpoint.getStateDocument()).equals(checkpoint.getStateHash())) {
            throw new CheckpointException("CHECKPOINT_CORRUPT", "Agent checkpoint integrity validation failed");
        }
        if (checkpoint.getStateSchemaVersion() != AgentState.CURRENT_SCHEMA_VERSION
                || !AgentState.CURRENT_RUNTIME_VERSION.equals(checkpoint.getRuntimeVersion())) {
            throw new CheckpointException("CHECKPOINT_INCOMPATIBLE", "Agent checkpoint was created by an incompatible runtime");
        }
        try {
            AgentState state = mapper.readValue(checkpoint.getStateDocument(), AgentState.class);
            if (!AgentState.CURRENT_PROMPT_VERSION.equals(state.promptVersion())) {
                throw new CheckpointException("CHECKPOINT_INCOMPATIBLE", "Agent checkpoint prompt version is incompatible");
            }
            return state;
        }
        catch (CheckpointException exception) { throw exception; }
        catch (Exception exception) { throw new CheckpointException("CHECKPOINT_INVALID", "Agent checkpoint state is invalid", exception); }
    }

    public AgentCheckpoint require(String checkpointId) {
        return checkpoints.findById(checkpointId).orElseThrow(() -> new CheckpointException("CHECKPOINT_NOT_FOUND", "Agent checkpoint was not found"));
    }

    public List<AgentCheckpoint> list(String runId) { return checkpoints.findByKnowledgeRunIdOrderByCheckpointSequenceAsc(runId); }
    public void delete(String runId) { checkpoints.deleteByKnowledgeRunId(runId); }

    public static class CheckpointException extends RuntimeException {
        private final String code;
        public CheckpointException(String code, String message) { super(message); this.code = code; }
        public CheckpointException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
        public String getCode() { return code; }
    }
}
