package com.voicenote.service;

import com.voicenote.domain.TaskAttempt;
import com.voicenote.domain.AttemptStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AttemptStateTest {
    @Test
    void submissionCanOnlyBeClaimedOnce() {
        TaskAttempt attempt = new TaskAttempt("task", 1);
        assertThat(attempt.claimSubmission()).isTrue();
        assertThat(attempt.claimSubmission()).isFalse();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUBMITTING);
    }
}
