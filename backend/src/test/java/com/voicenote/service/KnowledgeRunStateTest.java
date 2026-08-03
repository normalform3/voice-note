package com.voicenote.service;

import com.voicenote.domain.KnowledgeRun;
import com.voicenote.domain.KnowledgeRunStatus;
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
}
