package com.voicenote.repository;

import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.StageAttemptStatus;
import com.voicenote.domain.TaskStageAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskStageAttemptRepository extends JpaRepository<TaskStageAttempt, String> {
    List<TaskStageAttempt> findByTranscriptionTaskIdOrderByQueuedAtAsc(String taskId);
    Optional<TaskStageAttempt> findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(String taskId, PipelineStage stage);
    List<TaskStageAttempt> findTop50ByStatusAndNextRetryAtBeforeOrderByNextRetryAtAsc(StageAttemptStatus status, Instant time);
    List<TaskStageAttempt> findTop50ByStatusAndLeaseUntilBeforeOrderByLeaseUntilAsc(StageAttemptStatus status, Instant time);
    void deleteByTranscriptionTaskId(String taskId);
}
