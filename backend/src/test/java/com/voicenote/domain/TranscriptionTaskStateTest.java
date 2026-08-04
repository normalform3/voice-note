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
}
