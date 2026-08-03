package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.repository.TaskAttemptRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class TranscriptionTaskService {
    private static final String PIPELINE_VERSION = "asr-v1";
    private static final String CREATE_OPERATION = "CREATE_TRANSCRIPTION_TASK";
    private static final String RETRY_OPERATION = "RETRY_TRANSCRIPTION_TASK";
    private final TranscriptionTaskRepository tasks;
    private final TaskAttemptRepository attempts;
    private final AudioBlobRepository blobs;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final ObjectMapper mapper;

    public TranscriptionTaskService(TranscriptionTaskRepository tasks, TaskAttemptRepository attempts, AudioBlobRepository blobs,
                                    IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper) {
        this.tasks = tasks; this.attempts = attempts; this.blobs = blobs; this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper;
    }

    @Transactional
    public TranscriptionTask create(String ownerId, String key, CreateTaskCommand command) {
        AsrConfig config = command.asrConfig() == null ? AsrConfig.defaultConfig() : command.asrConfig().normalized();
        String requestHash = Hashing.canonicalJsonHash(new CreateTaskCommand(command.audioBlobId(), config));
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, requestHash);
        if (record.getResourceId() != null) return ownedTask(ownerId, record.getResourceId());
        AudioBlob blob = blobs.findById(command.audioBlobId()).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "Audio was not found"));
        if (blob.getStatus() != BlobStatus.READY) throw new ApiException(HttpStatus.CONFLICT, "AUDIO_NOT_READY", "Wait for the upload to finish before creating a task");
        String configHash = Hashing.canonicalJsonHash(config);
        TranscriptionTask task = tasks.findByOwnerIdAndAudioBlobIdAndAsrConfigHashAndPipelineVersion(ownerId, blob.getId(), configHash, PIPELINE_VERSION)
                .orElseGet(() -> {
                    TranscriptionTask created = tasks.save(new TranscriptionTask(ownerId, blob.getId(), configHash, PIPELINE_VERSION));
                    outbox.enqueue("transcription_task", created.getId(), EventType.TRANSCRIPTION_REQUESTED);
                    return created;
                });
        try { idempotency.complete(record, task.getId(), mapper.writeValueAsString(TaskView.from(task))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent task response", exception); }
        return task;
    }

    @Transactional
    public TranscriptionTask retry(String ownerId, String key, String taskId) {
        IdempotencyRecord record = idempotency.reserve(ownerId, RETRY_OPERATION, key, Hashing.sha256(taskId));
        if (record.getResourceId() != null) return ownedTask(ownerId, record.getResourceId());
        TranscriptionTask task = ownedTask(ownerId, taskId);
        if (!(task.getStatus() == TaskStatus.RETRYABLE_FAILED || task.getStatus() == TaskStatus.FINAL_FAILED || task.getStatus() == TaskStatus.SUBMISSION_UNKNOWN)) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_RETRYABLE", "Only terminal or unknown submissions can be retried");
        }
        int number = task.nextAttemptNumber();
        attempts.save(new TaskAttempt(task.getId(), number));
        task.mark(TaskStatus.QUEUED);
        outbox.enqueue("transcription_task", task.getId(), EventType.TRANSCRIPTION_REQUESTED);
        try { idempotency.complete(record, task.getId(), mapper.writeValueAsString(TaskView.from(task))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent retry response", exception); }
        return tasks.save(task);
    }

    @Transactional
    public void ensureFirstAttempt(String taskId) {
        TranscriptionTask task = tasks.findById(taskId).orElseThrow();
        if (task.getCurrentAttemptNumber() == 0) {
            int number = task.nextAttemptNumber();
            attempts.save(new TaskAttempt(task.getId(), number));
            tasks.save(task);
        }
    }

    @Transactional(readOnly = true)
    public TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }

    public record CreateTaskCommand(String audioBlobId, AsrConfig asrConfig) { }
    public record AsrConfig(List<String> languageHints, boolean diarizationEnabled, Integer speakerCount) {
        public static AsrConfig defaultConfig() { return new AsrConfig(List.of("zh", "en"), false, null); }
        public AsrConfig normalized() { return new AsrConfig(languageHints == null || languageHints.isEmpty() ? List.of("zh", "en") : languageHints.stream().sorted().toList(), diarizationEnabled, diarizationEnabled ? speakerCount : null); }
    }
    public record TaskView(String id, TaskStatus status, int currentAttemptNumber, int transcriptVersion, String failureCode, String failureMessage) {
        public static TaskView from(TranscriptionTask task) { return new TaskView(task.getId(), task.getStatus(), task.getCurrentAttemptNumber(), task.getTranscriptVersion(), task.getFailureCode(), task.getFailureMessage()); }
    }
}
