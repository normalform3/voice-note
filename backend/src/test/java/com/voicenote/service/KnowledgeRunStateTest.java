package com.voicenote.service;

import com.voicenote.domain.KnowledgeRun;
import com.voicenote.domain.KnowledgeRunStatus;
import com.voicenote.domain.AgentScopeType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import com.voicenote.agent.AgentPhase;
import com.voicenote.agent.AgentState;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRunStateTest {
    @Test
    void capsTheAgentAtItsConfiguredToolBudget() {
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", 2);
        run.queue();
        assertThat(run.start()).isTrue();
        assertThat(run.consumeTool()).isTrue();
        assertThat(run.consumeTool()).isTrue();
        assertThat(run.consumeTool()).isFalse();
        assertThat(run.getStatus()).isEqualTo(KnowledgeRunStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void independentlyCapsModelCallsTurnsAndTools() {
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                "knowledge-qa", "v1", "{}", "hash", 1, 1, 1);
        run.queue(); assertThat(run.start()).isTrue();

        assertThat(run.consumeModelCall()).isTrue();
        assertThat(run.consumeModelCall()).isFalse();
        assertThat(run.consumeTurn()).isTrue();
        assertThat(run.consumeTurn()).isFalse();
        assertThat(run.consumeTool()).isTrue();
        assertThat(run.consumeTool()).isFalse();
        assertThat(run.getStatus()).isEqualTo(KnowledgeRunStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void recoveryAdvancesTheEpochAndReplayKeepsFrozenRemainingBudgets() {
        KnowledgeRun parent = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                "knowledge-qa", "v1", "{}", Hashing.sha256("{}"), 4, 3, 2);
        parent.queue(); assertThat(parent.start()).isTrue();
        assertThat(parent.getExecutionEpoch()).isEqualTo(1);
        ReflectionTestUtils.setField(parent, "leaseUntil", Instant.now().minusSeconds(1));

        assertThat(parent.start()).isTrue();
        assertThat(parent.getExecutionEpoch()).isEqualTo(2);
        assertThat(parent.getRecoveryCount()).isEqualTo(1);

        AgentState source = AgentState.initial(AgentPhase.MODEL_DECISION, "knowledge-qa", "v1", Hashing.sha256("{}"), List.of())
                .withFrozenContext("model", "prompt", "{}",
                        List.of(new AgentState.DocumentSnapshot("task", "document", "index", "{}")), 4, 3, 2, 120_000)
                .withRuntimeStats(2, 1, 1, 4_000);
        KnowledgeRun replay = KnowledgeRun.replayOf(parent, "checkpoint", source);

        assertThat(replay.getParentRunId()).isEqualTo(parent.getId());
        assertThat(replay.getRootRunId()).isEqualTo(parent.getId());
        assertThat(replay.getModelCallsUsed()).isEqualTo(2);
        assertThat(replay.getAgentTurnsUsed()).isEqualTo(1);
        assertThat(replay.getToolCallsUsed()).isEqualTo(1);
        assertThat(replay.getActiveDurationMs()).isEqualTo(4_000);
        assertThat(replay.getMaxActiveDurationMs()).isEqualTo(120_000);
    }
}
