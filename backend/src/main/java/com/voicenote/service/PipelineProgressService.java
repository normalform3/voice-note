package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.config.AppProperties;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.repository.TaskStageAttemptRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class PipelineProgressService {
    private final TranscriptionTaskRepository tasks;
    private final TaskStageAttemptRepository stages;
    private final KnowledgeDocumentRepository documents;
    private final ProgressEventPublisher progressEvents;
    private final OutboxService outbox;
    private final AppProperties properties;

    public PipelineProgressService(TranscriptionTaskRepository tasks, TaskStageAttemptRepository stages,
                                   KnowledgeDocumentRepository documents, ProgressEventPublisher progressEvents, OutboxService outbox, AppProperties properties) {
        this.tasks = tasks; this.stages = stages; this.documents = documents; this.progressEvents = progressEvents; this.outbox = outbox; this.properties = properties;
    }

    @Transactional
    public void initialize(TranscriptionTask task) {
        if (stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.UPLOAD_COMPLETED).isEmpty()) {
            TaskStageAttempt upload = new TaskStageAttempt(task.getId(), PipelineStage.UPLOAD_COMPLETED, 1);
            upload.start(); upload.succeed("{\"contentReady\":true}"); stages.save(upload);
            stages.save(new TaskStageAttempt(task.getId(), PipelineStage.ASR_SUBMIT, 1));
            task.advance(PipelineStage.ASR_SUBMIT, 10); tasks.save(task);
        }
    }

    @Transactional
    public boolean begin(String taskId, PipelineStage stage) {
        TaskStageAttempt attempt = latestOrCreate(taskId, stage);
        if (!attempt.start()) return false;
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        task.advance(stage, progressFor(stage)); task.mark(statusFor(stage));
        stages.save(attempt); tasks.save(task); return true;
    }

    @Transactional
    public void succeeded(String taskId, PipelineStage stage, String snapshot, PipelineStage next) {
        TaskStageAttempt attempt = latest(taskId, stage);
        if (attempt.getStatus() != StageAttemptStatus.SUCCEEDED) { attempt.succeed(snapshot); stages.save(attempt); }
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (stage == PipelineStage.TRANSCRIPT_PERSIST) task.transcriptPersisted();
        if (next != null) {
            if (stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, next).isEmpty()) {
                stages.save(new TaskStageAttempt(taskId, next, 1));
            }
            task.advance(next, progressFor(next));
            task.mark(statusFor(next));
        }
        if (stage == PipelineStage.KNOWLEDGE_INDEX) task.completePipeline();
        tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void retryLater(String taskId, PipelineStage stage, String code, String message, int retryNumber) {
        TaskStageAttempt attempt = latest(taskId, stage);
        int seconds = switch (retryNumber) { case 1 -> 5; case 2 -> 15; default -> 45; };
        if (retryNumber > 3) { failed(taskId, stage, code, message, false); return; }
        attempt.retry(code, message, Instant.now().plusSeconds(seconds)); stages.save(attempt);
        TranscriptionTask task = tasks.findById(taskId).orElseThrow(); task.fail(TaskStatus.RETRYABLE_FAILED, code, message); tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void failed(String taskId, PipelineStage stage, String code, String message, boolean unknown) {
        TaskStageAttempt attempt = latest(taskId, stage); attempt.fail(code, message, unknown); stages.save(attempt);
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        task.advance(stage, progressFor(stage)); task.fail(unknown ? TaskStatus.SUBMISSION_UNKNOWN : TaskStatus.FINAL_FAILED, code, message); tasks.save(task); notifyTask(task);
    }

    @Transactional
    public PipelineStage retryStage(String ownerId, String taskId, PipelineStage requested) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        TaskStageAttempt prior = stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, requested).orElse(null);
        if (prior == null) {
            stages.save(new TaskStageAttempt(taskId, requested, 1));
            task.advance(requested, progressFor(requested)); task.mark(statusFor(requested)); tasks.save(task);
            return requested;
        }
        if (prior.getStatus() != StageAttemptStatus.FAILED && prior.getStatus() != StageAttemptStatus.UNKNOWN && prior.getStatus() != StageAttemptStatus.RETRY_WAIT) {
            throw new ApiException(HttpStatus.CONFLICT, "STAGE_NOT_RETRYABLE", "Only a failed, unknown, or waiting stage can be retried");
        }
        stages.save(new TaskStageAttempt(taskId, requested, prior.getAttemptNumber() + 1));
        task.advance(requested, progressFor(requested)); task.mark(statusFor(requested)); tasks.save(task);
        return requested;
    }

    @Transactional(readOnly = true)
    public List<RetryWork> dueRetries() {
        return stages.findTop50ByStatusAndNextRetryAtBeforeOrderByNextRetryAtAsc(StageAttemptStatus.RETRY_WAIT, Instant.now())
                .stream().map(value -> new RetryWork(value.getId(), value.getTranscriptionTaskId(), value.getStage())).toList();
    }

    @Transactional
    public boolean activateRetry(String stageAttemptId) {
        TaskStageAttempt previous = stages.findById(stageAttemptId).orElse(null);
        if (previous == null || previous.getStatus() != StageAttemptStatus.RETRY_WAIT || previous.getNextRetryAt().isAfter(Instant.now())) return false;
        previous.retried(); stages.save(previous);
        stages.save(new TaskStageAttempt(previous.getTranscriptionTaskId(), previous.getStage(), previous.getAttemptNumber() + 1));
        TranscriptionTask task = tasks.findById(previous.getTranscriptionTaskId()).orElseThrow();
        task.advance(previous.getStage(), progressFor(previous.getStage())); task.mark(statusFor(previous.getStage())); tasks.save(task);
        return true;
    }

    @Transactional(readOnly = true)
    public TaskProgressView ownedView(String ownerId, String taskId) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        return view(task);
    }
    @Transactional(readOnly = true)
    public List<TaskProgressView> ownedViews(String ownerId) { return tasks.findByOwnerIdOrderByUpdatedAtDesc(ownerId).stream().map(this::view).toList(); }
    @Transactional(readOnly = true)
    public TaskProgressView viewForNotification(String taskId) { return view(tasks.findById(taskId).orElseThrow()); }

    private TaskProgressView view(TranscriptionTask task) {
        List<TaskStageAttempt> attempts = stages.findByTranscriptionTaskIdOrderByQueuedAtAsc(task.getId());
        Map<PipelineStage, List<TaskStageAttempt>> grouped = new EnumMap<>(PipelineStage.class);
        attempts.forEach(item -> grouped.computeIfAbsent(item.getStage(), ignored -> new ArrayList<>()).add(item));
        List<StageView> stageViews = new ArrayList<>();
        for (PipelineStage stage : PipelineStage.values()) {
            List<TaskStageAttempt> history = grouped.get(stage); if (history == null || history.isEmpty()) continue;
            TaskStageAttempt current = history.get(history.size() - 1);
            long totalWait = history.stream().map(TaskStageAttempt::getWaitDurationMs).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
            stageViews.add(new StageView(stage, current.getStatus(), current.getAttemptNumber(), current.getQueuedAt(), current.getStartedAt(), current.getCompletedAt(),
                    current.getWaitDurationMs(), totalWait, current.getNextRetryAt(), current.getErrorCode(), current.getErrorMessage()));
        }
        KnowledgeDocumentView document = documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .map(value -> new KnowledgeDocumentView(value.getId(), value.getTitle(), value.getStatus().name(), value.getFailureMessage())).orElse(null);
        List<PipelineStage> retryable = stageViews.stream().filter(stage -> stage.status() == StageAttemptStatus.FAILED || stage.status() == StageAttemptStatus.UNKNOWN || stage.status() == StageAttemptStatus.RETRY_WAIT).map(StageView::stage).toList();
        return new TaskProgressView(task.getId(), task.getAudioBlobId(), task.getStatus(), task.getCurrentStage(), task.getProgressPercent(), task.isTranscriptReady(),
                task.getCurrentAttemptNumber(), task.getTranscriptVersion(), task.getFailureCode(), task.getFailureMessage(), task.getFailedStage(), retryable, stageViews, document);
    }

    private TaskStageAttempt latest(String taskId, PipelineStage stage) { return stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, stage).orElseThrow(); }
    private TaskStageAttempt latestOrCreate(String taskId, PipelineStage stage) {
        return stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, stage)
                .orElseGet(() -> stages.save(new TaskStageAttempt(taskId, stage, 1)));
    }
    private void notifyTask(TranscriptionTask task) {
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("transcription_task", task.getId(), EventType.PROGRESS_CHANGED);
        else progressEvents.publish(new ProgressEventPublisher.ProgressNotification(task.getOwnerId(), "task-stage-settled", task.getId()));
    }
    private static int progressFor(PipelineStage stage) { return switch (stage) { case UPLOAD_COMPLETED -> 5; case ASR_SUBMIT -> 15; case ASR_POLL -> 40; case TRANSCRIPT_PERSIST -> 60; case KNOWLEDGE_PREPARE -> 70; case KNOWLEDGE_INDEX -> 85; case COMPLETED -> 100; }; }
    private static TaskStatus statusFor(PipelineStage stage) { return switch (stage) { case ASR_SUBMIT -> TaskStatus.SUBMITTING; case ASR_POLL, TRANSCRIPT_PERSIST, KNOWLEDGE_PREPARE -> TaskStatus.PROVIDER_RUNNING; case KNOWLEDGE_INDEX, COMPLETED -> TaskStatus.INDEXING; case UPLOAD_COMPLETED -> TaskStatus.QUEUED; }; }

    public record KnowledgeDocumentView(String id, String title, String status, String failureMessage) { }
    public record StageView(PipelineStage stage, StageAttemptStatus status, int attemptNumber, Instant queuedAt, Instant startedAt, Instant completedAt,
                            Long waitDurationMs, long totalWaitDurationMs, Instant nextRetryAt, String errorCode, String errorMessage) { }
    public record TaskProgressView(String id, String audioBlobId, TaskStatus status, PipelineStage currentStage, int progressPercent, boolean transcriptReady,
                                   int currentAttemptNumber, int transcriptVersion, String failureCode, String failureMessage, PipelineStage failedStage,
                                   List<PipelineStage> retryableStages, List<StageView> stages, KnowledgeDocumentView knowledgeDocument) { }
    public record RetryWork(String stageAttemptId, String taskId, PipelineStage stage) { }
}
