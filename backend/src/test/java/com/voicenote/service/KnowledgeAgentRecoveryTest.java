package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentPhase;
import com.voicenote.agent.AgentState;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeAgentRecoveryTest {
    @Test
    void leaseRecoveryInterruptsTheOldStepAndStartsANewExecutionEpoch() {
        Fixture fixture = new Fixture();
        KnowledgeRun run = fixture.runningRun();
        run.useCheckpoint("checkpoint-1", AgentState.CURRENT_RUNTIME_VERSION);
        KnowledgeRunStep abandoned = new KnowledgeRunStep(run.getId(), 0, AgentStepType.TOOL, "call", "lookup", "{}",
                run.getExecutionEpoch(), "checkpoint-1");
        ReflectionTestUtils.setField(run, "leaseUntil", Instant.now().minusSeconds(1));
        when(fixture.runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(fixture.steps.findByKnowledgeRunIdAndStatus(run.getId(), AgentStepStatus.RUNNING)).thenReturn(List.of(abandoned));
        when(fixture.steps.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeAgentService.RunWork work = fixture.service.claim(run.getId());

        assertThat(work.recovered()).isTrue();
        assertThat(work.executionEpoch()).isEqualTo(2);
        assertThat(run.getRecoveryCount()).isEqualTo(1);
        assertThat(abandoned.getStatus()).isEqualTo(AgentStepStatus.INTERRUPTED);
        assertThat(abandoned.getErrorCode()).isEqualTo("WORKER_LEASE_EXPIRED");
        verify(fixture.steps, times(2)).save(any(KnowledgeRunStep.class));
    }

    @Test
    void staleEpochAndAlreadySettledStepsCannotCommitAgain() {
        Fixture fixture = new Fixture();
        KnowledgeRun run = fixture.runningRun();
        ReflectionTestUtils.setField(run, "executionEpoch", 2L);
        when(fixture.runs.findById(run.getId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> fixture.service.succeedAgentStep(run.getId(), 1, "old-step", "{}", "late", 1,
                null, null, terminalState(), null, true)).isInstanceOf(KnowledgeAgentService.StaleAgentExecutionException.class);

        KnowledgeRunStep settled = new KnowledgeRunStep(run.getId(), 1, AgentStepType.TOOL, "call", "lookup", "{}", 2, null);
        settled.succeed("{}", "done", 1);
        when(fixture.steps.findById(settled.getId())).thenReturn(Optional.of(settled));
        assertThatThrownBy(() -> fixture.service.succeedAgentStep(run.getId(), 2, settled.getId(), "{}", "duplicate", 1,
                null, null, terminalState(), null, true)).isInstanceOf(KnowledgeAgentService.StaleAgentExecutionException.class);
        verify(fixture.checkpoints, never()).save(any(), any(), any(), anyBoolean());
    }

    @Test
    void replayCreatesAnImmutableChildFromTheCheckpointSnapshotAndIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        KnowledgeRun parent = fixture.runningRun();
        parent.succeed("{}");
        String skillSnapshot = "{}";
        AgentState state = AgentState.initial(AgentPhase.MODEL_DECISION, "knowledge-qa", "v1", Hashing.sha256(skillSnapshot), List.of())
                .withFrozenContext("frozen-model", "frozen-prompt", skillSnapshot,
                        List.of(new AgentState.DocumentSnapshot("task", "document", "index-v1", "{\"title\":\"Frozen\"}")),
                        5, 4, 3, 90_000).withRuntimeStats(2, 1, 1, 5_000);
        String stateDocument = new ObjectMapper().writeValueAsString(state);
        AgentCheckpoint source = new AgentCheckpoint(parent.getId(), 2, AgentState.CURRENT_SCHEMA_VERSION,
                AgentState.CURRENT_RUNTIME_VERSION, AgentPhase.MODEL_DECISION, "step", stateDocument,
                Hashing.sha256(stateDocument), true);
        IdempotencyRecord record = new IdempotencyRecord("owner", "REPLAY_AGENT_RUN", "key", "hash");
        when(fixture.runs.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(fixture.runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.runDocuments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.checkpoints.require(source.getId())).thenReturn(source);
        when(fixture.checkpoints.read(source)).thenReturn(state);
        when(fixture.idempotency.reserve(eq("owner"), eq("REPLAY_AGENT_RUN"), eq("key"), anyString())).thenReturn(record);

        KnowledgeRun child = fixture.service.replayAgent("owner", "key", parent.getId(), source.getId());

        assertThat(child.getParentRunId()).isEqualTo(parent.getId());
        assertThat(child.getRootRunId()).isEqualTo(parent.getId());
        assertThat(child.getModelId()).isEqualTo("frozen-model");
        assertThat(child.getModelCallsUsed()).isEqualTo(2);
        assertThat(child.getMaxActiveDurationMs()).isEqualTo(90_000);
        verify(fixture.runDocuments).save(argThat(document -> document.getKnowledgeRunId().equals(child.getId())
                && document.getKnowledgeIndexVersionId().equals("index-v1")
                && document.getMetadataSnapshot().contains("Frozen")));
        verify(fixture.outbox).enqueue("knowledge_run", child.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);

        record.complete(child.getId(), 200, "{}");
        when(fixture.runs.findById(child.getId())).thenReturn(Optional.of(child));
        KnowledgeRun sameChild = fixture.service.replayAgent("owner", "key", parent.getId(), source.getId());
        assertThat(sameChild.getId()).isEqualTo(child.getId());
        verify(fixture.runs, times(2)).save(any());
    }

    @Test
    void replayDoesNotRevealRunsOwnedByAnotherUser() {
        Fixture fixture = new Fixture();
        KnowledgeRun parent = fixture.runningRun();
        when(fixture.runs.findById(parent.getId())).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> fixture.service.replayAgent("other-owner", "key", parent.getId(), "checkpoint"))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");
        verify(fixture.checkpoints, never()).require(anyString());
    }

    private static AgentState terminalState() {
        return AgentState.initial(AgentPhase.TERMINAL, "knowledge-qa", "v1", "hash", List.of());
    }

    private static class Fixture {
        final KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        final KnowledgeRunStepRepository steps = mock(KnowledgeRunStepRepository.class);
        final KnowledgeRunDocumentRepository runDocuments = mock(KnowledgeRunDocumentRepository.class);
        final AgentCheckpointStore checkpoints = mock(AgentCheckpointStore.class);
        final IdempotencyService idempotency = mock(IdempotencyService.class);
        final OutboxService outbox = mock(OutboxService.class);
        final KnowledgeAgentService service = new KnowledgeAgentService(runs, mock(KnowledgeRunEvidenceRepository.class),
                runDocuments, steps, mock(KnowledgeRunSourceRepository.class),
                mock(TranscriptionTaskRepository.class), mock(KnowledgeDocumentRepository.class),
                mock(OrganizedDocumentRepository.class), mock(OrganizedDocumentBlockRepository.class), mock(KnowledgeIndexVersionRepository.class),
                mock(KnowledgeChunkRepository.class), mock(TranscriptSegmentRepository.class), idempotency, outbox, new ObjectMapper().findAndRegisterModules(),
                new AppProperties(), new ProgressEventPublisher(event -> { }), mock(com.voicenote.agent.AgentSkillRegistry.class),
                mock(com.voicenote.agent.AgentMetrics.class), checkpoints, new DocumentQaPolicy());

        KnowledgeRun runningRun() {
            KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                    "knowledge-qa", "v1", "{}", "hash", 4, 4, 4);
            run.queue(); run.start();
            return run;
        }
    }
}
