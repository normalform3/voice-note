package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.provider.AsrProvider;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class AsrWorker {
    private static final Logger log = LoggerFactory.getLogger(AsrWorker.class);
    private final AppProperties properties;
    private final AsrAttemptState state;
    private final AsrProvider provider;
    public AsrWorker(AppProperties properties, AsrAttemptState state, AsrProvider provider) { this.properties = properties; this.state = state; this.provider = provider; }
    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() {
        if (!properties.getWorkers().isEnabled()) return;
        state.activateDueRetries();
        state.queuedAttempts().forEach(this::submit);
        state.duePolls().forEach(this::poll);
    }
    private void submit(String attemptId) {
        AsrAttemptState.SubmissionWork work = state.claimSubmission(attemptId);
        if (work == null) return;
        try { state.recordSubmission(work.attemptId(), provider.submit(work.audio(), work.options())); }
        catch (ProviderException exception) {
            log.warn("ASR submission failed: attemptId={}, code={}, kind={}", work.attemptId(), exception.getCode(), exception.getKind());
            state.recordFailure(work.attemptId(), PipelineStage.ASR_SUBMIT, exception);
        }
        catch (RuntimeException exception) {
            log.error("ASR submission crashed: attemptId={}", work.attemptId(), exception);
            state.recordUnexpectedFailure(work.attemptId(), PipelineStage.ASR_SUBMIT, exception);
        }
    }
    private void poll(String attemptId) {
        AsrAttemptState.PollWork work = state.claimPoll(attemptId);
        if (work == null) return;
        try { state.recordPoll(work.attemptId(), provider.poll(work.providerTaskId())); }
        catch (ProviderException exception) {
            log.warn("ASR polling failed: attemptId={}, code={}, kind={}", work.attemptId(), exception.getCode(), exception.getKind());
            state.recordFailure(work.attemptId(), PipelineStage.ASR_POLL, exception);
        }
        catch (RuntimeException exception) {
            log.error("ASR polling crashed: attemptId={}", work.attemptId(), exception);
            state.recordUnexpectedFailure(work.attemptId(), PipelineStage.ASR_POLL, exception);
        }
    }

    @Service
    public static class AsrAttemptState {
        private final TaskAttemptRepository attempts; private final TranscriptionTaskRepository tasks; private final AudioBlobRepository blobs;
        private final ProviderInvocationRepository invocations; private final TranscriptSegmentRepository segments;
        private final RawTranscriptDocumentRepository rawDocuments;
        private final TranscriptSpeakerRepository speakers;
        private final ObjectStorage storage;
        private final PipelineProgressService pipeline;
        private final ObjectMapper mapper;
        public AsrAttemptState(TaskAttemptRepository attempts, TranscriptionTaskRepository tasks, AudioBlobRepository blobs, ProviderInvocationRepository invocations,
                               TranscriptSegmentRepository segments, RawTranscriptDocumentRepository rawDocuments, TranscriptSpeakerRepository speakers, ObjectStorage storage,
                               PipelineProgressService pipeline, ObjectMapper mapper) {
            this.attempts = attempts; this.tasks = tasks; this.blobs = blobs; this.invocations = invocations; this.segments = segments; this.rawDocuments = rawDocuments; this.storage = storage; this.pipeline = pipeline;
            this.speakers = speakers; this.mapper = mapper;
        }
        @Transactional(readOnly = true) public List<String> queuedAttempts() { return attempts.findTop20ByStatusOrderByCreatedAtAsc(AttemptStatus.QUEUED).stream().map(TaskAttempt::getId).toList(); }
        @Transactional(readOnly = true) public List<String> duePolls() { return attempts.findTop20ByStatusAndNextPollAtBeforeOrderByNextPollAtAsc(AttemptStatus.PROVIDER_RUNNING, Instant.now()).stream().map(TaskAttempt::getId).toList(); }
        @Transactional
        public SubmissionWork claimSubmission(String attemptId) {
            TaskAttempt attempt = attempts.findById(attemptId).orElse(null); if (attempt == null) return null;
            if (!pipeline.begin(attempt.getTranscriptionTaskId(), PipelineStage.ASR_SUBMIT) || !attempt.claimSubmission()) return null;
            TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow(); task.mark(TaskStatus.RUNNING);
            ProviderInvocation invocation = invocations.findByTaskAttemptIdAndInvocationType(attempt.getId(), "ASR_SUBMIT")
                    .orElseGet(() -> new ProviderInvocation(attempt.getId(), "ASR_SUBMIT", Hashing.sha256(task.getId() + ":" + attempt.getAttemptNumber())));
            if (invocation.getStatus() != InvocationStatus.READY) return null;
            invocation.markInFlight(); invocations.save(invocation); attempts.save(attempt); tasks.save(task);
            return new SubmissionWork(attempt.getId(), blobs.findById(task.getAudioBlobId()).orElseThrow(), asrOptions(task));
        }
        @Transactional
        public void recordSubmission(String attemptId, AsrProvider.AsrSubmission submission) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            if (task.isCancelled()) return;
            attempt.submitted(submission.providerTaskId(), submission.providerInputUrl()); task.mark(TaskStatus.RUNNING);
            invocations.findByTaskAttemptIdAndInvocationType(attemptId, "ASR_SUBMIT").ifPresent(value -> { value.markSucceeded("{\"providerTaskId\":\"" + submission.providerTaskId() + "\"}"); invocations.save(value); });
            attempts.save(attempt); tasks.save(task);
            pipeline.succeeded(task.getId(), PipelineStage.ASR_SUBMIT, "{\"submitted\":true}", PipelineStage.ASR_POLL);
        }
        @Transactional
        public PollWork claimPoll(String attemptId) {
            TaskAttempt attempt = attempts.findById(attemptId).orElse(null);
            if (attempt == null || attempt.getStatus() != AttemptStatus.PROVIDER_RUNNING) return null;
            var stage = pipeline.ownedView(tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow().getOwnerId(), attempt.getTranscriptionTaskId()).stages().stream()
                    .filter(value -> value.stage() == PipelineStage.ASR_POLL).reduce((first, second) -> second).orElse(null);
            if (stage == null || (stage.status() != StageAttemptStatus.RUNNING && !pipeline.begin(attempt.getTranscriptionTaskId(), PipelineStage.ASR_POLL))) return null;
            return new PollWork(attemptId, attempt.getProviderTaskId());
        }
        @Transactional
        public void recordPoll(String attemptId, AsrProvider.AsrPollResult result) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            if (task.isCancelled()) return;
            if (result.status() == AsrProvider.AsrPollResult.Status.RUNNING) { attempt.reschedulePoll(); attempts.save(attempt); return; }
            if (result.status() == AsrProvider.AsrPollResult.Status.FAILED) { attempt.fail(AttemptStatus.FINAL_FAILED, result.errorCode(), result.errorMessage()); task.fail(TaskStatus.FINAL_FAILED, result.errorCode(), result.errorMessage()); attempts.save(attempt); tasks.save(task); pipeline.failed(task.getId(), PipelineStage.ASR_POLL, result.errorCode(), result.errorMessage(), false); return; }
            validateTranscript(result, asrConfig(task).diarizationEnabled());
            pipeline.succeeded(task.getId(), PipelineStage.ASR_POLL, "{\"completed\":true,\"segmentCount\":" + result.segments().size() + "}", PipelineStage.TRANSCRIPT_PERSIST);
            if (!pipeline.begin(task.getId(), PipelineStage.TRANSCRIPT_PERSIST)) return;
            int version = task.nextTranscriptVersion(); List<TranscriptSegment> created = new java.util.ArrayList<>();
            for (int index = 0; index < result.segments().size(); index++) { AsrProvider.AsrSegment segment = result.segments().get(index); created.add(segments.save(new TranscriptSegment(task.getId(), version, index, segment.speakerId(), segment.startMs(), segment.endMs(), segment.text()))); }
            for (String speakerId : created.stream().map(TranscriptSegment::getAsrSpeakerId).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
                speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(task.getId(), version, speakerId)
                        .orElseGet(() -> speakers.save(new TranscriptSpeaker(task.getId(), version, speakerId)));
            }
            persistRawDocument(task, attempt, version, created, result.rawResultDocument());
            attempt.succeed(); attempts.save(attempt); tasks.save(task);
            pipeline.succeeded(task.getId(), PipelineStage.TRANSCRIPT_PERSIST, "{\"transcriptVersion\":" + version + ",\"segmentCount\":" + created.size() + "}", null);
            pipeline.awaitFormalDocument(task.getId());
        }
        @Transactional
        public void recordFailure(String attemptId, PipelineStage stage, ProviderException exception) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            if (task.isCancelled()) return;
            if (exception.getKind() == ProviderException.Kind.RETRYABLE_REJECTION) {
                int retryNumber = pipeline.ownedView(task.getOwnerId(), task.getId()).stages().stream().filter(value -> value.stage() == stage).reduce((first, second) -> second).map(value -> value.attemptNumber()).orElse(1);
                pipeline.retryLater(task.getId(), stage, exception.getCode(), exception.getMessage(), retryNumber);
                return;
            }
            AttemptStatus attemptStatus = switch (exception.getKind()) { case AMBIGUOUS_SUBMISSION -> AttemptStatus.SUBMISSION_UNKNOWN; case RETRYABLE_REJECTION -> AttemptStatus.RETRYABLE_FAILED; case FINAL_REJECTION -> AttemptStatus.FINAL_FAILED; };
            TaskStatus taskStatus = TaskStatus.valueOf(attemptStatus.name()); attempt.fail(attemptStatus, exception.getCode(), exception.getMessage()); task.fail(taskStatus, exception.getCode(), exception.getMessage());
            invocations.findByTaskAttemptIdAndInvocationType(attemptId, "ASR_SUBMIT").ifPresent(value -> { if (attemptStatus == AttemptStatus.SUBMISSION_UNKNOWN) value.markUnknown(); invocations.save(value); });
            attempts.save(attempt); tasks.save(task);
            pipeline.failed(task.getId(), stage, exception.getCode(), exception.getMessage(), attemptStatus == AttemptStatus.SUBMISSION_UNKNOWN);
        }
        @Transactional
        public void recordUnexpectedFailure(String attemptId, PipelineStage stage, RuntimeException exception) {
            String detail = exception.getMessage();
            String message = detail == null || detail.isBlank() ? exception.getClass().getSimpleName() : detail;
            recordFailure(attemptId, stage, new ProviderException(ProviderException.Kind.FINAL_REJECTION,
                    "ASR_STAGE_FAILED", "转写处理发生异常：" + message));
        }
        @Transactional
        public void activateDueRetries() {
            for (PipelineProgressService.RetryWork retry : pipeline.dueRetries()) {
                if (retry.stage() != PipelineStage.ASR_SUBMIT && retry.stage() != PipelineStage.ASR_POLL) continue;
                if (!pipeline.activateRetry(retry.stageAttemptId())) continue;
                TaskAttempt attempt = attempts.findTopByTranscriptionTaskIdOrderByAttemptNumberDesc(retry.taskId()).orElse(null);
                if (attempt == null) continue;
                if (retry.stage() == PipelineStage.ASR_SUBMIT) {
                    invocations.findByTaskAttemptIdAndInvocationType(attempt.getId(), "ASR_SUBMIT").ifPresent(value -> { value.resetForRetry(); invocations.save(value); });
                    attempt.retrySubmission(); attempts.save(attempt);
                } else if (retry.stage() == PipelineStage.ASR_POLL) {
                    attempt.retryPollNow(); attempts.save(attempt);
                }
            }
        }
        private AsrProvider.AsrOptions asrOptions(TranscriptionTask task) {
            TranscriptionTaskService.AsrConfig config = asrConfig(task);
            return new AsrProvider.AsrOptions(config.languageHints(), config.diarizationEnabled(), config.speakerCount());
        }
        private TranscriptionTaskService.AsrConfig asrConfig(TranscriptionTask task) {
            try {
                TranscriptionTaskService.AsrConfig config = task.getAsrConfig() == null
                        ? TranscriptionTaskService.AsrConfig.defaultConfig()
                        : mapper.readValue(task.getAsrConfig(), TranscriptionTaskService.AsrConfig.class);
                return config.normalized();
            } catch (Exception exception) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_CONFIG_INVALID", "Stored ASR configuration is invalid");
            }
        }
        private static void validateTranscript(AsrProvider.AsrPollResult result, boolean diarizationEnabled) {
            AsrProvider.AsrAudioMetadata metadata = result.audioMetadata();
            if (diarizationEnabled) {
                if (metadata == null || metadata.channelCount() == null || metadata.channelCount() != 1) {
                    throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_DIARIZATION_REQUIRES_MONO", "Speaker diarization requires a mono audio track");
                }
                if (metadata.durationMs() != null && metadata.durationMs() > 7_200_000L) {
                    throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_DIARIZATION_DURATION_EXCEEDED", "Speaker diarization supports recordings up to two hours");
                }
            }
            long previousEnd = -1;
            for (AsrProvider.AsrSegment segment : result.segments()) {
                if ((diarizationEnabled && (segment.speakerId() == null || segment.speakerId().isBlank())) || segment.text() == null || segment.text().isBlank()
                        || segment.startMs() < 0 || segment.endMs() < segment.startMs() || segment.startMs() < previousEnd) {
                    throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_RESULT_INVALID", "ASR did not return ordered sentence results");
                }
                previousEnd = segment.endMs();
            }
        }
        private void persistRawDocument(TranscriptionTask task, TaskAttempt attempt, int version, List<TranscriptSegment> segments, String rawResult) {
            String raw = rawResult == null || rawResult.isBlank() ? "{}" : rawResult;
            String objectKey = "transcripts/" + task.getId() + "/v" + version + "/dashscope-result.json";
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            String content = segments.stream().map(segment -> "[" + segment.getStartMs() + "-" + segment.getEndMs() + "ms] " + segment.getAsrSpeakerId() + ": " + segment.getTextContent())
                    .collect(java.util.stream.Collectors.joining("\n"));
            // The unique source key makes a recovered poll idempotent and prevents rewriting an accepted artifact.
            if (rawDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), version).isPresent()) return;
            try {
                storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "application/json");
                rawDocuments.save(new RawTranscriptDocument(task.getOwnerId(), task.getId(), version, attempt.getProviderTaskId(), objectKey,
                        Hashing.sha256(raw), content, segments.size()));
            } catch (RuntimeException exception) {
                storage.removeQuietly(objectKey);
                throw exception;
            }
        }
        public record SubmissionWork(String attemptId, AudioBlob audio, AsrProvider.AsrOptions options) { }
        public record PollWork(String attemptId, String providerTaskId) { }
    }
}
