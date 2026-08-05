package com.voicenote.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TranscriptionTaskStateTest {
    @Test
    void exposesThreeProcessingPhasesAndKeepsCancellationTerminal() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "three-phase-v1");

        assertThat(task.getCurrentPhase()).isEqualTo(PipelinePhase.TRANSCRIPTION);
        task.advance(PipelineStage.DOCUMENT_ORGANIZATION, 70);
        assertThat(task.getCurrentPhase()).isEqualTo(PipelinePhase.DOCUMENT_ORGANIZATION);
        assertThat(task.cancel()).isTrue();
        assertThat(task.isCancelled()).isTrue();
        assertThat(task.cancel()).isFalse();
    }

    @Test
    void waitsForUserActionsBetweenTheThreePersistedArtifacts() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "manual-gates-v2");

        task.transcriptPersisted();
        task.awaitFormalDocument();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_FORMAL_DOCUMENT);
        assertThat(task.getCurrentStage()).isEqualTo(PipelineStage.RAW_DOCUMENT_READY);

        task.advance(PipelineStage.DOCUMENT_ORGANIZATION, 70);
        task.awaitKnowledgeBuild();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_KNOWLEDGE_BUILD);
        assertThat(task.getCurrentStage()).isEqualTo(PipelineStage.FORMAL_DOCUMENT_READY);
    }
}
