package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.provider.AsrProvider;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class AsrWorker {
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
        catch (ProviderException exception) { state.recordFailure(work.attemptId(), PipelineStage.ASR_SUBMIT, exception); }
    }
    private void poll(String attemptId) {
        AsrAttemptState.PollWork work = state.claimPoll(attemptId);
        if (work == null) return;
        try { state.recordPoll(work.attemptId(), provider.poll(work.providerTaskId())); }
        catch (ProviderException exception) { state.recordFailure(work.attemptId(), PipelineStage.ASR_POLL, exception); }
    }

    @Service
    public static class AsrAttemptState {
        private final TaskAttemptRepository attempts; private final TranscriptionTaskRepository tasks; private final AudioBlobRepository blobs;
        private final ProviderInvocationRepository invocations; private final TranscriptSegmentRepository segments;
        private final TranscriptSpeakerRepository speakers;
        private final DocumentOrganizationService organizedDocuments;
        private final PipelineProgressService pipeline;
        private final ObjectMapper mapper;
        public AsrAttemptState(TaskAttemptRepository attempts, TranscriptionTaskRepository tasks, AudioBlobRepository blobs, ProviderInvocationRepository invocations,
                               TranscriptSegmentRepository segments, TranscriptSpeakerRepository speakers, DocumentOrganizationService organizedDocuments,
                               PipelineProgressService pipeline, ObjectMapper mapper) {
            this.attempts = attempts; this.tasks = tasks; this.blobs = blobs; this.invocations = invocations; this.segments = segments; this.organizedDocuments = organizedDocuments; this.pipeline = pipeline;
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
            validateDiarizedTranscript(result);
            pipeline.succeeded(task.getId(), PipelineStage.ASR_POLL, "{\"completed\":true,\"segmentCount\":" + result.segments().size() + "}", PipelineStage.TRANSCRIPT_PERSIST);
            if (!pipeline.begin(task.getId(), PipelineStage.TRANSCRIPT_PERSIST)) return;
            int version = task.nextTranscriptVersion(); List<TranscriptSegment> created = new java.util.ArrayList<>();
            for (int index = 0; index < result.segments().size(); index++) { AsrProvider.AsrSegment segment = result.segments().get(index); created.add(segments.save(new TranscriptSegment(task.getId(), version, index, segment.speakerId(), segment.startMs(), segment.endMs(), segment.text()))); }
            for (String speakerId : created.stream().map(TranscriptSegment::getAsrSpeakerId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))) {
                speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(task.getId(), version, speakerId)
                        .orElseGet(() -> speakers.save(new TranscriptSpeaker(task.getId(), version, speakerId)));
            }
            attempt.succeed(); attempts.save(attempt); tasks.save(task);
            pipeline.succeeded(task.getId(), PipelineStage.TRANSCRIPT_PERSIST, "{\"transcriptVersion\":" + version + ",\"segmentCount\":" + created.size() + "}", PipelineStage.DOCUMENT_ORGANIZATION);
            if (task.isCancelled()) return;
            organizedDocuments.createForTranscript(task, blobs.findById(task.getAudioBlobId()).orElseThrow());
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
            try {
                TranscriptionTaskService.AsrConfig config = task.getAsrConfig() == null
                        ? TranscriptionTaskService.AsrConfig.defaultConfig()
                        : mapper.readValue(task.getAsrConfig(), TranscriptionTaskService.AsrConfig.class);
                return new AsrProvider.AsrOptions(config.languageHints(), config.speakerCount());
            } catch (Exception exception) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_CONFIG_INVALID", "Stored ASR configuration is invalid");
            }
        }
        private static void validateDiarizedTranscript(AsrProvider.AsrPollResult result) {
            AsrProvider.AsrAudioMetadata metadata = result.audioMetadata();
            if (metadata == null || metadata.channelCount() == null || metadata.channelCount() != 1) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_DIARIZATION_REQUIRES_MONO", "Speaker diarization requires a mono audio track");
            }
            if (metadata.durationMs() != null && metadata.durationMs() > 7_200_000L) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_DIARIZATION_DURATION_EXCEEDED", "Speaker diarization supports recordings up to two hours");
            }
            long previousEnd = -1;
            for (AsrProvider.AsrSegment segment : result.segments()) {
                if (segment.speakerId() == null || segment.speakerId().isBlank() || segment.text() == null || segment.text().isBlank()
                        || segment.startMs() < 0 || segment.endMs() < segment.startMs() || segment.startMs() < previousEnd) {
                    throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ASR_DIARIZATION_RESULT_INVALID", "ASR did not return ordered, speaker-labelled sentence results");
                }
                previousEnd = segment.endMs();
            }
        }
        public record SubmissionWork(String attemptId, AudioBlob audio, AsrProvider.AsrOptions options) { }
        public record PollWork(String attemptId, String providerTaskId) { }
    }
}
