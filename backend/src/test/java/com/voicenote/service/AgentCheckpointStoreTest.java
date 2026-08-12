package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.AgentPhase;
import com.voicenote.agent.AgentState;
import com.voicenote.domain.AgentCheckpoint;
import com.voicenote.domain.KnowledgeRun;
import com.voicenote.repository.AgentCheckpointRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCheckpointStoreTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistsAndRestoresACompleteVersionedState() {
        AgentCheckpointRepository repository = mock(AgentCheckpointRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AgentCheckpointStore store = new AgentCheckpointStore(repository, mapper);
        KnowledgeRun run = runningRun();
        AgentState state = state();

        AgentCheckpoint checkpoint = store.save(run, state, "step-1", true);
        AgentState restored = store.read(checkpoint);

        assertThat(restored).isEqualTo(state.withRuntimeStats(0, 0, 0, 0));
        assertThat(restored.documentSnapshots()).hasSize(1);
        assertThat(restored.promptSnapshot()).isEqualTo("system prompt v1");
        assertThat(restored.memoryEnabled()).isTrue();
        assertThat(restored.conversationContextSnapshot()).isEqualTo("earlier conversation");
        assertThat(run.getCurrentCheckpointId()).isEqualTo(checkpoint.getId());
    }

    @Test
    void rejectsHashCorruptionAndRuntimeIncompatibility() throws Exception {
        AgentCheckpointStore store = new AgentCheckpointStore(mock(AgentCheckpointRepository.class), mapper);
        String document = mapper.writeValueAsString(state());
        AgentCheckpoint corrupt = new AgentCheckpoint("run", 0, AgentState.CURRENT_SCHEMA_VERSION,
                AgentState.CURRENT_RUNTIME_VERSION, AgentPhase.MODEL_DECISION, null, document, "bad", true);
        AgentCheckpoint incompatible = new AgentCheckpoint("run", 0, AgentState.CURRENT_SCHEMA_VERSION,
                "old-runtime", AgentPhase.MODEL_DECISION, null, document, Hashing.sha256(document), true);

        assertThatThrownBy(() -> store.read(corrupt)).isInstanceOfSatisfying(AgentCheckpointStore.CheckpointException.class,
                value -> assertThat(value.getCode()).isEqualTo("CHECKPOINT_CORRUPT"));
        assertThatThrownBy(() -> store.read(incompatible)).isInstanceOfSatisfying(AgentCheckpointStore.CheckpointException.class,
                value -> assertThat(value.getCode()).isEqualTo("CHECKPOINT_INCOMPATIBLE"));
    }

    @Test
    void rejectsAnIncompatiblePromptSnapshotVersion() throws Exception {
        AgentCheckpointStore store = new AgentCheckpointStore(mock(AgentCheckpointRepository.class), mapper);
        ObjectNode document = (ObjectNode) mapper.valueToTree(state());
        document.put("promptVersion", "old-prompt");
        String serialized = mapper.writeValueAsString(document);
        AgentCheckpoint checkpoint = new AgentCheckpoint("run", 0, AgentState.CURRENT_SCHEMA_VERSION,
                AgentState.CURRENT_RUNTIME_VERSION, AgentPhase.MODEL_DECISION, null, serialized,
                Hashing.sha256(serialized), true);

        assertThatThrownBy(() -> store.read(checkpoint)).isInstanceOfSatisfying(AgentCheckpointStore.CheckpointException.class,
                value -> assertThat(value.getCode()).isEqualTo("CHECKPOINT_INCOMPATIBLE"));
    }

    private KnowledgeRun runningRun() {
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", 4);
        run.queue(); run.start();
        return run;
    }

    private AgentState state() {
        return AgentState.initial(AgentPhase.MODEL_DECISION, "knowledge-qa", "v1", Hashing.sha256("{}"), List.of())
                .withFrozenContext("model", "system prompt v1", true, "earlier conversation", "{}",
                        List.of(new AgentState.DocumentSnapshot("task", "document", "index-v1", "{\"title\":\"note\"}")),
                        7, 6, 8, 120_000);
    }
}
