package com.voicenote.repository;

import com.voicenote.domain.AttemptStatus;
import com.voicenote.domain.TaskAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, String> {
    Optional<TaskAttempt> findByTranscriptionTaskIdAndAttemptNumber(String taskId, int attemptNumber);
    List<TaskAttempt> findTop20ByStatusOrderByCreatedAtAsc(AttemptStatus status);
    List<TaskAttempt> findTop20ByStatusAndNextPollAtBeforeOrderByNextPollAtAsc(AttemptStatus status, Instant before);
}
