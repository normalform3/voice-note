package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.config.AppProperties;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.repository.KnowledgeIndexStageAttemptRepository;
import com.voicenote.repository.KnowledgeIndexVersionRepository;
import com.voicenote.repository.OrganizedDocumentRepository;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PipelineProgressService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final TranscriptionTaskRepository tasks;
    private final TaskStageAttemptRepository stages;
    private final KnowledgeDocumentRepository documents;
    private final OrganizedDocumentRepository organizedDocuments;
    private final KnowledgeIndexVersionRepository indexVersions;
    private final KnowledgeIndexStageAttemptRepository indexStages;
    private final ProgressEventPublisher progressEvents;
    private final OutboxService outbox;
    private final AppProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public PipelineProgressService(TranscriptionTaskRepository tasks, TaskStageAttemptRepository stages,
                                   KnowledgeDocumentRepository documents, OrganizedDocumentRepository organizedDocuments,
                                   KnowledgeIndexVersionRepository indexVersions, KnowledgeIndexStageAttemptRepository indexStages,
                                   ProgressEventPublisher progressEvents, OutboxService outbox, AppProperties properties) {
        this.tasks = tasks; this.stages = stages; this.documents = documents; this.organizedDocuments = organizedDocuments;
        this.indexVersions = indexVersions; this.indexStages = indexStages;
        this.progressEvents = progressEvents; this.outbox = outbox; this.properties = properties;
    }
    PipelineProgressService(TranscriptionTaskRepository tasks, TaskStageAttemptRepository stages,
                            KnowledgeDocumentRepository documents, OrganizedDocumentRepository organizedDocuments,
                            ProgressEventPublisher progressEvents, OutboxService outbox, AppProperties properties) {
        this(tasks, stages, documents, organizedDocuments, null, null, progressEvents, outbox, properties);
    }

    @Transactional
    public void initialize(TranscriptionTask task) {
        initialize(task, Instant.now());
    }

    @Transactional
    public void initialize(TranscriptionTask task, Instant uploadStartedAt) {
        if (stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(task.getId(), PipelineStage.UPLOAD_COMPLETED).isEmpty()) {
            TaskStageAttempt upload = new TaskStageAttempt(task.getId(), PipelineStage.UPLOAD_COMPLETED, 1, uploadStartedAt);
            upload.start(); upload.succeed("{\"contentReady\":true}"); stages.save(upload);
            stages.save(new TaskStageAttempt(task.getId(), PipelineStage.ASR_SUBMIT, 1));
            task.advance(PipelineStage.ASR_SUBMIT, 10); tasks.save(task);
        }
    }

    @Transactional
    public boolean begin(String taskId, PipelineStage stage) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminal(task)) return false;
        TaskStageAttempt attempt = latestOrCreate(taskId, stage);
        if (stage == PipelineStage.KNOWLEDGE_PREPARE && task.getStatus() == TaskStatus.WAITING_FOR_KNOWLEDGE_BUILD
                && attempt.getStatus() != StageAttemptStatus.QUEUED && attempt.getStatus() != StageAttemptStatus.RUNNING
                && attempt.getStatus() != StageAttemptStatus.RETRY_WAIT) {
            attempt = stages.save(new TaskStageAttempt(taskId, stage, attempt.getAttemptNumber() + 1));
        }
        if (!attempt.start()) return false;
        task.advance(stage, progressFor(stage)); task.mark(statusFor(stage));
        stages.save(attempt); tasks.save(task); notifyTask(task); return true;
    }

    /** Makes user-triggered work visible immediately so queue time starts at the request, not at worker pickup. */
    @Transactional
    public boolean queue(String taskId, PipelineStage stage) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminal(task)) return false;
        TaskStageAttempt prior = stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, stage).orElse(null);
        if (prior != null && !(stage == PipelineStage.DOCUMENT_ORGANIZATION && task.getStatus() == TaskStatus.WAITING_FOR_FORMAL_DOCUMENT
                && prior.getStatus() != StageAttemptStatus.QUEUED && prior.getStatus() != StageAttemptStatus.RUNNING
                && prior.getStatus() != StageAttemptStatus.RETRY_WAIT)) return false;
        stages.save(new TaskStageAttempt(taskId, stage, prior == null ? 1 : prior.getAttemptNumber() + 1));
        task.advance(stage, progressFor(stage));
        task.mark(statusFor(stage));
        tasks.save(task);
        notifyTask(task);
        return true;
    }

    /** Records the exact configured model only once a worker is about to invoke it. */
    @Transactional
    public void recordModelInvocation(String taskId, PipelineStage stage, String modelId) {
        if (modelId == null || modelId.isBlank()) return;
        TaskStageAttempt attempt = latest(taskId, stage);
        if (modelId.equals(attempt.getModelId())) return;
        attempt.recordModelInvocation(modelId);
        stages.save(attempt);
        tasks.findById(taskId).ifPresent(this::notifyTask);
    }

    @Transactional
    public void succeeded(String taskId, PipelineStage stage, String snapshot, PipelineStage next) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminal(task)) return;
        TaskStageAttempt attempt = latest(taskId, stage);
        if (attempt.getStatus() != StageAttemptStatus.SUCCEEDED) { attempt.succeed(snapshot); stages.save(attempt); }
        if (stage == PipelineStage.TRANSCRIPT_PERSIST) task.transcriptPersisted();
        if (next != null) {
            TaskStageAttempt prior = stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, next).orElse(null);
            if (prior == null || (task.getCurrentStage() == stage && prior.getStatus() != StageAttemptStatus.QUEUED
                    && prior.getStatus() != StageAttemptStatus.RUNNING && prior.getStatus() != StageAttemptStatus.RETRY_WAIT)) {
                stages.save(new TaskStageAttempt(taskId, next, prior == null ? 1 : prior.getAttemptNumber() + 1));
            }
            task.advance(next, progressFor(next));
            task.mark(statusFor(next));
        }
        if (stage == PipelineStage.KNOWLEDGE_INDEX) task.completePipeline();
        tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void completeWithoutKnowledge(String taskId) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminal(task)) return;
        task.completePipeline();
        tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void awaitFormalDocument(String taskId) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (task.isCancelled()) return;
        task.awaitFormalDocument(); tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void awaitKnowledgeBuild(String taskId) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (task.isCancelled()) return;
        task.awaitKnowledgeBuild(); tasks.save(task); notifyTask(task);
    }

    @Transactional
    public int invalidateForSpeakerCorrection(String taskId) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        task.speakerCorrectionApplied(); tasks.save(task); notifyTask(task);
        return task.getSpeakerCorrectionRevision();
    }

    @Transactional
    public void retryLater(String taskId, PipelineStage stage, String code, String message, int retryNumber) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (isTerminal(task)) return;
        TaskStageAttempt attempt = latest(taskId, stage);
        int seconds = switch (retryNumber) { case 1 -> 5; case 2 -> 15; default -> 45; };
        if (retryNumber > 3) { failed(taskId, stage, code, message, false); return; }
        attempt.retry(code, message, Instant.now().plusSeconds(seconds)); stages.save(attempt);
        task.fail(TaskStatus.RETRY_WAIT, code, message); tasks.save(task); notifyTask(task);
    }

    @Transactional
    public void failed(String taskId, PipelineStage stage, String code, String message, boolean unknown) {
        TaskStageAttempt attempt = latestOrCreate(taskId, stage); attempt.fail(code, message, unknown); stages.save(attempt);
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (task.isCancelled()) return;
        task.advance(stage, progressFor(stage)); task.fail(TaskStatus.FAILED, code, message); tasks.save(task); notifyTask(task);
    }

    /** Settles audio-pipeline work when its durable dispatch to RocketMQ cannot be confirmed. */
    @Transactional
    public void failDelivery(OutboxEvent event, RuntimeException failure) {
        String message = "无法投递处理任务到消息队列：" + failureMessage(failure);
        switch (event.getEventType()) {
            case TRANSCRIPTION_REQUESTED -> failed(event.getAggregateId(), PipelineStage.ASR_SUBMIT, "MESSAGE_DELIVERY_FAILED", message, false);
            case DOCUMENT_ORGANIZATION_REQUESTED -> organizedDocuments.findById(event.getAggregateId()).ifPresent(document -> {
                document.fail(message); organizedDocuments.save(document);
                failed(document.getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, "MESSAGE_DELIVERY_FAILED", message, false);
            });
            case KNOWLEDGE_INDEX_REQUESTED -> {
                if (indexVersions == null || indexStages == null) break;
                indexVersions.findById(event.getAggregateId()).ifPresent(index -> documents.findById(index.getKnowledgeDocumentId()).ifPresent(document -> {
                    index.fail(message); indexVersions.save(index);
                    KnowledgeIndexStage stage = index.getCurrentStage() == null ? KnowledgeIndexStage.INGEST : index.getCurrentStage();
                    indexStages.findTopByKnowledgeIndexVersionIdAndStageOrderByAttemptNumberDesc(index.getId(), stage).ifPresent(attempt -> { attempt.fail("MESSAGE_DELIVERY_FAILED", message); indexStages.save(attempt); });
                    if (!document.hasActiveIndexVersion()) {
                        document.fail(message); documents.save(document);
                        failed(document.getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX, "MESSAGE_DELIVERY_FAILED", message, false);
                    }
                }));
            }
            default -> { }
        }
    }

    @Transactional
    public PipelineStage retryStage(String ownerId, String taskId, PipelineStage requested) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (task.isCancelled()) throw new ApiException(HttpStatus.CONFLICT, "TASK_CANCELLED", "Cancelled tasks must be created again");
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

    /** Starts a fresh ASR submission after cancellation without reviving cancelled stage attempts. */
    @Transactional
    public void restartCancelledTask(String ownerId, String taskId) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (!task.isCancelled()) throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_CANCELLED", "Only cancelled tasks can be submitted again");
        int nextStageAttempt = stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, PipelineStage.ASR_SUBMIT)
                .map(previous -> previous.getAttemptNumber() + 1).orElse(1);
        stages.save(new TaskStageAttempt(taskId, PipelineStage.ASR_SUBMIT, nextStageAttempt));
        task.advance(PipelineStage.ASR_SUBMIT, progressFor(PipelineStage.ASR_SUBMIT));
        task.mark(TaskStatus.QUEUED);
        tasks.save(task);
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
        TranscriptionTask task = tasks.findById(previous.getTranscriptionTaskId()).orElseThrow();
        if (isTerminal(task)) return false;
        previous.retried(); stages.save(previous);
        stages.save(new TaskStageAttempt(previous.getTranscriptionTaskId(), previous.getStage(), previous.getAttemptNumber() + 1));
        task.advance(previous.getStage(), progressFor(previous.getStage())); task.mark(statusFor(previous.getStage())); tasks.save(task);
        return true;
    }

    @Transactional
    public int recoverExpiredLeases() {
        int recovered = 0;
        for (TaskStageAttempt attempt : stages.findTop50ByStatusAndLeaseUntilBeforeOrderByLeaseUntilAsc(StageAttemptStatus.RUNNING, Instant.now())) {
            TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElse(null);
            if (task == null || task.isCancelled()) continue;
            int seconds = switch (attempt.getAttemptNumber()) { case 1 -> 5; case 2 -> 15; default -> 45; };
            attempt.retry("WORKER_LEASE_EXPIRED", "Worker lease expired before the stage settled", Instant.now().plusSeconds(seconds));
            stages.save(attempt);
            task.fail(TaskStatus.RETRY_WAIT, "WORKER_LEASE_EXPIRED", "Worker lease expired before the stage settled");
            tasks.save(task); notifyTask(task); recovered++;
        }
        return recovered;
    }

    @Transactional
    public boolean cancel(String ownerId, String taskId) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_CANCELLABLE", "Completed tasks cannot be cancelled");
        }
        if (!task.cancel()) return false;
        stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, task.getCurrentStage())
                .ifPresent(attempt -> { attempt.cancel(); stages.save(attempt); });
        tasks.save(task); notifyTask(task); return true;
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
                    current.getWaitDurationMs(), totalWait, current.getNextRetryAt(), current.getErrorCode(), current.getErrorMessage(), current.getModelId()));
        }
        KnowledgeDocumentView document = documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .map(value -> new KnowledgeDocumentView(value.getId(), value.getTitle(), value.getStatus().name(), value.getFailureMessage(), indexBuild(value.getId()))).orElse(null);
        OrganizedDocumentView organized = organizedDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .map(value -> new OrganizedDocumentView(value.getId(), value.getTitle(), value.getStatus().name(), value.getFailureMessage())).orElse(null);
        List<PipelineStage> retryable = stageViews.stream().filter(stage -> stage.status() == StageAttemptStatus.FAILED || stage.status() == StageAttemptStatus.UNKNOWN || stage.status() == StageAttemptStatus.RETRY_WAIT).map(StageView::stage).toList();
        return new TaskProgressView(task.getId(), task.getAudioBlobId(), task.getStatus(), task.getCurrentPhase(), task.getCurrentStage(), task.getProgressPercent(), task.isTranscriptReady(),
                task.getCreatedAt(), tasks.findDurationMs(task.getId(), task.getTranscriptVersion()),
                task.getOccurredAt(), task.getSceneType(), task.getSubject(), parseTags(task),
                task.getCurrentAttemptNumber(), task.getTranscriptVersion(), task.getSpeakerCorrectionRevision(), task.getFailureCode(), task.getFailureMessage(), task.getFailedStage(), retryable, stageViews, document, organized);
    }

    private static List<String> parseTags(TranscriptionTask task) {
        String tags = task.getTags();
        if (tags == null || tags.isBlank()) return List.of();
        try { return List.copyOf(JSON.readValue(tags, STRING_LIST)); }
        catch (Exception exception) {
            log.warn("Ignoring invalid tags JSON for transcription task {}", task.getId());
            return List.of();
        }
    }

    private TaskStageAttempt latest(String taskId, PipelineStage stage) { return stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, stage).orElseThrow(); }
    private TaskStageAttempt latestOrCreate(String taskId, PipelineStage stage) {
        return stages.findTopByTranscriptionTaskIdAndStageOrderByAttemptNumberDesc(taskId, stage)
                .orElseGet(() -> stages.save(new TaskStageAttempt(taskId, stage, 1)));
    }
    private void notifyTask(TranscriptionTask task) {
        progressEvents.publish(new ProgressEventPublisher.ProgressNotification(task.getOwnerId(), "task-stage-settled", task.getId()));
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("transcription_task", task.getId(), EventType.PROGRESS_CHANGED);
    }
    private static boolean isTerminal(TranscriptionTask task) {
        return task.isCancelled() || task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.SUCCEEDED;
    }
    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
    private static int progressFor(PipelineStage stage) { return switch (stage) { case UPLOAD_COMPLETED -> 5; case ASR_SUBMIT -> 10; case ASR_POLL -> 40; case TRANSCRIPT_PERSIST, RAW_DOCUMENT_READY -> 60; case DOCUMENT_ORGANIZATION -> 70; case FORMAL_DOCUMENT_READY -> 80; case KNOWLEDGE_PREPARE -> 85; case KNOWLEDGE_INDEX -> 90; case COMPLETED -> 100; }; }
    private static TaskStatus statusFor(PipelineStage stage) { return stage == PipelineStage.UPLOAD_COMPLETED ? TaskStatus.QUEUED : TaskStatus.RUNNING; }

    private KnowledgeIndexBuildView indexBuild(String documentId) {
        if (indexVersions == null || indexStages == null) return null;
        return indexVersions.findTopByKnowledgeDocumentIdOrderByGenerationDesc(documentId).map(index -> {
            List<KnowledgeIndexStageView> stages = indexStages.findByKnowledgeIndexVersionIdOrderByQueuedAtAsc(index.getId()).stream()
                    .map(value -> new KnowledgeIndexStageView(value.getStage().name(), value.getStatus().name(), value.getProgressPercent(), value.getCompletedCount(), value.getTotalCount(), value.getErrorMessage())).toList();
            int progress = stages.stream().mapToInt(value -> switch (value.stage()) { case "INGEST" -> value.progressPercent() * 15 / 100; case "CHUNK" -> 15 + value.progressPercent() * 25 / 100; case "INDEX" -> 40 + value.progressPercent() * 60 / 100; default -> 0; }).max().orElse(0);
            if (index.getStatus() == KnowledgeIndexVersionStatus.READY) progress = 100;
            return new KnowledgeIndexBuildView(index.getId(), index.getStatus().name(), index.getCurrentStage() == null ? null : index.getCurrentStage().name(), progress,
                    index.getTopicCount(), index.getChunkCount(), index.getIndexedChunkCount(), index.getFailureMessage(), stages);
        }).orElse(null);
    }
    public record KnowledgeIndexStageView(String stage, String status, int progressPercent, int completedCount, int totalCount, String errorMessage) { }
    public record KnowledgeIndexBuildView(String id, String status, String currentStage, int progressPercent, int topicCount, int chunkCount, int indexedChunkCount,
                                          String failureMessage, List<KnowledgeIndexStageView> stages) { }
    public record KnowledgeDocumentView(String id, String title, String status, String failureMessage, KnowledgeIndexBuildView currentBuild) { }
    public record OrganizedDocumentView(String id, String title, String status, String failureMessage) { }
    public record StageView(PipelineStage stage, StageAttemptStatus status, int attemptNumber, Instant queuedAt, Instant startedAt, Instant completedAt,
                            Long waitDurationMs, long totalWaitDurationMs, Instant nextRetryAt, String errorCode, String errorMessage, String modelId) { }
    public record TaskProgressView(String id, String audioBlobId, TaskStatus status, PipelinePhase currentPhase, PipelineStage currentStage, int progressPercent, boolean transcriptReady,
                                   Instant createdAt, Long durationMs,
                                   Instant occurredAt, SceneType sceneType, String subject, List<String> tags,
                                   int currentAttemptNumber, int transcriptVersion, int speakerCorrectionRevision, String failureCode, String failureMessage, PipelineStage failedStage,
                                   List<PipelineStage> retryableStages, List<StageView> stages, KnowledgeDocumentView knowledgeDocument, OrganizedDocumentView organizedDocument) { }
    public record RetryWork(String stageAttemptId, String taskId, PipelineStage stage) { }
}
