package com.voicenote.service;

import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.StageAttemptStatus;
import com.voicenote.domain.TaskStageAttempt;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStageAttemptTest {
    @Test
    void recordsQueueWaitAndKeepsTerminalRetryHistory() {
        TaskStageAttempt attempt = new TaskStageAttempt("task", PipelineStage.ASR_SUBMIT, 1);

        assertThat(attempt.start()).isTrue();
        assertThat(attempt.getWaitDurationMs()).isGreaterThanOrEqualTo(0);
        attempt.retry("TEMPORARY", "try later", Instant.now().plusSeconds(5));

        assertThat(attempt.getStatus()).isEqualTo(StageAttemptStatus.RETRY_WAIT);
        assertThat(attempt.getNextRetryAt()).isAfter(Instant.now());
        attempt.retried();
        assertThat(attempt.getStatus()).isEqualTo(StageAttemptStatus.RETRIED);
    }
}
