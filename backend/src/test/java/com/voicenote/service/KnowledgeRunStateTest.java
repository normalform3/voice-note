package com.voicenote.service;

import com.voicenote.domain.KnowledgeRun;
import com.voicenote.domain.KnowledgeRunStatus;
import com.voicenote.domain.AgentScopeType;
import org.junit.jupiter.api.Test;
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
}
