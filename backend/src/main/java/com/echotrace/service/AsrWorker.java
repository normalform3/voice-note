package com.echotrace.service;

import com.echotrace.config.AppProperties;
import com.echotrace.domain.*;
import com.echotrace.provider.AsrProvider;
import com.echotrace.provider.ProviderException;
import com.echotrace.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
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
        state.queuedAttempts().forEach(this::submit);
        state.duePolls().forEach(this::poll);
    }
    private void submit(String attemptId) {
        AsrAttemptState.SubmissionWork work = state.claimSubmission(attemptId);
        if (work == null) return;
        try { state.recordSubmission(work.attemptId(), provider.submit(work.audio())); }
        catch (ProviderException exception) { state.recordFailure(work.attemptId(), exception); }
    }
    private void poll(String attemptId) {
        AsrAttemptState.PollWork work = state.claimPoll(attemptId);
        if (work == null) return;
        try { state.recordPoll(work.attemptId(), provider.poll(work.providerTaskId())); }
        catch (ProviderException exception) { state.recordFailure(work.attemptId(), exception); }
    }

    @Service
    public static class AsrAttemptState {
        private final TaskAttemptRepository attempts; private final TranscriptionTaskRepository tasks; private final AudioBlobRepository blobs;
        private final ProviderInvocationRepository invocations; private final TranscriptSegmentRepository segments;
        public AsrAttemptState(TaskAttemptRepository attempts, TranscriptionTaskRepository tasks, AudioBlobRepository blobs, ProviderInvocationRepository invocations, TranscriptSegmentRepository segments) {
            this.attempts = attempts; this.tasks = tasks; this.blobs = blobs; this.invocations = invocations; this.segments = segments;
        }
        @Transactional(readOnly = true) public List<String> queuedAttempts() { return attempts.findTop20ByStatusOrderByCreatedAtAsc(AttemptStatus.QUEUED).stream().map(TaskAttempt::getId).toList(); }
        @Transactional(readOnly = true) public List<String> duePolls() { return attempts.findTop20ByStatusAndNextPollAtBeforeOrderByNextPollAtAsc(AttemptStatus.PROVIDER_RUNNING, Instant.now()).stream().map(TaskAttempt::getId).toList(); }
        @Transactional
        public SubmissionWork claimSubmission(String attemptId) {
            TaskAttempt attempt = attempts.findById(attemptId).orElse(null); if (attempt == null || !attempt.claimSubmission()) return null;
            TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow(); task.mark(TaskStatus.SUBMITTING);
            ProviderInvocation invocation = invocations.findByTaskAttemptIdAndInvocationType(attempt.getId(), "ASR_SUBMIT")
                    .orElseGet(() -> new ProviderInvocation(attempt.getId(), "ASR_SUBMIT", Hashing.sha256(task.getId() + ":" + attempt.getAttemptNumber())));
            if (invocation.getStatus() != InvocationStatus.READY) return null;
            invocation.markInFlight(); invocations.save(invocation); attempts.save(attempt); tasks.save(task);
            return new SubmissionWork(attempt.getId(), blobs.findById(task.getAudioBlobId()).orElseThrow());
        }
        @Transactional
        public void recordSubmission(String attemptId, AsrProvider.AsrSubmission submission) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            attempt.submitted(submission.providerTaskId(), submission.providerInputUrl()); task.mark(TaskStatus.PROVIDER_RUNNING);
            invocations.findByTaskAttemptIdAndInvocationType(attemptId, "ASR_SUBMIT").ifPresent(value -> { value.markSucceeded("{\"providerTaskId\":\"" + submission.providerTaskId() + "\"}"); invocations.save(value); });
            attempts.save(attempt); tasks.save(task);
        }
        @Transactional
        public PollWork claimPoll(String attemptId) { TaskAttempt attempt = attempts.findById(attemptId).orElse(null); return attempt == null || attempt.getStatus() != AttemptStatus.PROVIDER_RUNNING ? null : new PollWork(attemptId, attempt.getProviderTaskId()); }
        @Transactional
        public void recordPoll(String attemptId, AsrProvider.AsrPollResult result) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            if (result.status() == AsrProvider.AsrPollResult.Status.RUNNING) { attempt.reschedulePoll(); attempts.save(attempt); return; }
            if (result.status() == AsrProvider.AsrPollResult.Status.FAILED) { attempt.fail(AttemptStatus.FINAL_FAILED, result.errorCode(), result.errorMessage()); task.fail(TaskStatus.FINAL_FAILED, result.errorCode(), result.errorMessage()); attempts.save(attempt); tasks.save(task); return; }
            int version = task.nextTranscriptVersion();
            for (int index = 0; index < result.segments().size(); index++) { AsrProvider.AsrSegment segment = result.segments().get(index); segments.save(new TranscriptSegment(task.getId(), version, index, segment.speakerLabel(), segment.startMs(), segment.endMs(), segment.text())); }
            attempt.succeed(); task.mark(TaskStatus.SUCCEEDED); attempts.save(attempt); tasks.save(task);
        }
        @Transactional
        public void recordFailure(String attemptId, ProviderException exception) {
            TaskAttempt attempt = attempts.findById(attemptId).orElseThrow(); TranscriptionTask task = tasks.findById(attempt.getTranscriptionTaskId()).orElseThrow();
            AttemptStatus attemptStatus = switch (exception.getKind()) { case AMBIGUOUS_SUBMISSION -> AttemptStatus.SUBMISSION_UNKNOWN; case RETRYABLE_REJECTION -> AttemptStatus.RETRYABLE_FAILED; case FINAL_REJECTION -> AttemptStatus.FINAL_FAILED; };
            TaskStatus taskStatus = TaskStatus.valueOf(attemptStatus.name()); attempt.fail(attemptStatus, exception.getCode(), exception.getMessage()); task.fail(taskStatus, exception.getCode(), exception.getMessage());
            invocations.findByTaskAttemptIdAndInvocationType(attemptId, "ASR_SUBMIT").ifPresent(value -> { if (attemptStatus == AttemptStatus.SUBMISSION_UNKNOWN) value.markUnknown(); invocations.save(value); });
            attempts.save(attempt); tasks.save(task);
        }
        public record SubmissionWork(String attemptId, AudioBlob audio) { }
        public record PollWork(String attemptId, String providerTaskId) { }
    }
}
