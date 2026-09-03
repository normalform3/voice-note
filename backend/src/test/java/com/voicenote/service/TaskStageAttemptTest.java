package com.voicenote.service;

import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.StageAttemptStatus;
import com.voicenote.domain.TaskStageAttempt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStageAttemptTest {
    @Test
    void recordsQueueWaitAndKeepsTerminalRetryHistory() {
        TaskStageAttempt attempt = new TaskStageAttempt("task", PipelineStage.ASR_SUBMIT, 1, Instant.now().minusSeconds(4));

        assertThat(attempt.start()).isTrue();
        assertThat(attempt.getWaitDurationMs()).isGreaterThanOrEqualTo(3_900);
        attempt.retry("TEMPORARY", "try later", Instant.now().plusSeconds(5));

        assertThat(attempt.getStatus()).isEqualTo(StageAttemptStatus.RETRY_WAIT);
        assertThat(attempt.getNextRetryAt()).isAfter(Instant.now());
        attempt.recordModelInvocation("paraformer-v2");
        assertThat(attempt.getModelId()).isEqualTo("paraformer-v2");
        attempt.retried();
        assertThat(attempt.getStatus()).isEqualTo(StageAttemptStatus.RETRIED);
    }

    @Test
    void supportsLongRunningStageLeasesAndRenewal() {
        TaskStageAttempt attempt = new TaskStageAttempt("task", PipelineStage.DOCUMENT_ORGANIZATION, 1);

        assertThat(attempt.start(Duration.ofMinutes(10))).isTrue();
        assertThat(attempt.getLeaseUntil()).isAfter(Instant.now().plusSeconds(9 * 60));
        Instant originalLease = attempt.getLeaseUntil();

        attempt.renewLease(Duration.ofMinutes(10));

        assertThat(attempt.getLeaseUntil()).isAfterOrEqualTo(originalLease);
    }
}
