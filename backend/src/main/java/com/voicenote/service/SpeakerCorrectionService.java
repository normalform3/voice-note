package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SpeakerCorrectionService {
    private static final String CREATE_OPERATION = "CREATE_SPEAKER_CORRECTION_RUN";
    private static final String APPLY_OPERATION = "APPLY_SPEAKER_CORRECTION_RUN";
    public static final String TEMPLATE_VERSION = "speaker-correction-v1";
    private final SpeakerCorrectionRunRepository runs; private final SpeakerCorrectionSuggestionRepository suggestions;
    private final SpeakerCorrectionInvocationRepository invocations; private final TranscriptionTaskRepository tasks;
    private final TranscriptSegmentRepository segments; private final TranscriptSpeakerRepository speakers;
    private final IdempotencyService idempotency; private final OutboxService outbox; private final ObjectMapper mapper;
    private final AppProperties properties; private final ProgressEventPublisher progress; private final TranscriptSpeakerCorrectionService corrections;

    public SpeakerCorrectionService(SpeakerCorrectionRunRepository runs, SpeakerCorrectionSuggestionRepository suggestions,
                                    SpeakerCorrectionInvocationRepository invocations, TranscriptionTaskRepository tasks,
                                    TranscriptSegmentRepository segments, TranscriptSpeakerRepository speakers, IdempotencyService idempotency,
                                    OutboxService outbox, ObjectMapper mapper, AppProperties properties, ProgressEventPublisher progress,
                                    TranscriptSpeakerCorrectionService corrections) {
        this.runs = runs; this.suggestions = suggestions; this.invocations = invocations; this.tasks = tasks; this.segments = segments;
        this.speakers = speakers; this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
        this.progress = progress; this.corrections = corrections;
    }

    @Transactional
    public SpeakerCorrectionRun create(String ownerId, String key, String taskId, int expectedRevision) {
        TranscriptionTask task = ownedTask(ownerId, taskId);
        if (!task.isTranscriptReady()) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "AI speaker correction requires a completed original document");
        if (task.getSpeakerCorrectionRevision() != expectedRevision) throw revisionConflict();
        List<TranscriptSegment> source = activeSegments(task);
        String snapshotHash = snapshotHash(source);
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, Hashing.canonicalJsonHash(Map.of("taskId", taskId, "revision", expectedRevision)));
        if (record.getResourceId() != null) return ownedRun(ownerId, record.getResourceId());
        SpeakerCorrectionRun latest = runs.findTopByOwnerIdAndTranscriptionTaskIdOrderByCreatedAtDesc(ownerId, taskId).orElse(null);
        if (latest != null && latest.getTranscriptVersion() == task.getTranscriptVersion() && latest.getBaseRevision() == expectedRevision
                && latest.getSnapshotHash().equals(snapshotHash) && (latest.getStatus() == SpeakerCorrectionRunStatus.QUEUED
                || latest.getStatus() == SpeakerCorrectionRunStatus.RUNNING)) {
            completeIdempotency(record, latest.getId()); return latest;
        }
        SpeakerCorrectionRun run = runs.save(new SpeakerCorrectionRun(ownerId, taskId, task.getTranscriptVersion(), expectedRevision,
                snapshotHash, TEMPLATE_VERSION, properties.getDashscope().getChatModel()));
        outbox.enqueue("speaker_correction_run", run.getId(), EventType.SPEAKER_CORRECTION_REQUESTED);
        completeIdempotency(record, run.getId()); return run;
    }

    @Transactional
    public RunDetail latest(String ownerId, String taskId) {
        ownedTask(ownerId, taskId);
        SpeakerCorrectionRun run = runs.findTopByOwnerIdAndTranscriptionTaskIdOrderByCreatedAtDesc(ownerId, taskId).orElse(null);
        return run == null ? null : refreshAndDetail(run);
    }

    @Transactional
    public RunDetail detail(String ownerId, String runId) { return refreshAndDetail(ownedRun(ownerId, runId)); }

    @Transactional(readOnly = true)
    public List<String> queuedRunIds() {
        return runs.findTop10ByStatusOrderByCreatedAtAsc(SpeakerCorrectionRunStatus.QUEUED).stream().map(SpeakerCorrectionRun::getId).toList();
    }

    @Transactional
    public RunWork claim(String runId) {
        SpeakerCorrectionRun run = runs.findById(runId).orElse(null); if (run == null) return null;
        TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElse(null);
        if (task == null || task.getTranscriptVersion() != run.getTranscriptVersion() || task.getSpeakerCorrectionRevision() != run.getBaseRevision()) {
            run.stale(); runs.save(run); notifySettled(run); return null;
        }
        List<TranscriptSegment> source = activeSegments(task);
        if (!snapshotHash(source).equals(run.getSnapshotHash())) { run.stale(); runs.save(run); notifySettled(run); return null; }
        if (!run.start()) return null;
        runs.save(run);
        List<TranscriptSpeaker> roster = speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(task.getId(), task.getTranscriptVersion());
        return new RunWork(run.getId(), run.getOwnerId(), task.getId(), task.getTranscriptVersion(), task.getSceneType(), task.getSubject(), source, roster);
    }

    @Transactional
    public void recordInvocation(String runId, int chunkIndex, int attemptNumber, String prompt, String response, boolean unknown) {
        SpeakerCorrectionInvocation invocation = invocations.findByRunIdAndChunkIndexAndAttemptNumber(runId, chunkIndex, attemptNumber)
                .orElseGet(() -> invocations.save(new SpeakerCorrectionInvocation(runId, chunkIndex, attemptNumber, Hashing.sha256(prompt))));
        invocation.inFlight();
        if (unknown) invocation.unknown(); else invocation.succeeded(response);
        invocations.save(invocation);
    }

    @Transactional
    public void complete(String runId, List<SuggestionDraft> drafts, int rejectedCount) {
        SpeakerCorrectionRun run = runs.findById(runId).orElseThrow(); TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElseThrow();
        if (task.getTranscriptVersion() != run.getTranscriptVersion() || task.getSpeakerCorrectionRevision() != run.getBaseRevision()) {
            run.stale(); runs.save(run); notifySettled(run); return;
        }
        Map<String, TranscriptSegment> current = activeSegments(task).stream().collect(java.util.stream.Collectors.toMap(TranscriptSegment::getId, value -> value));
        suggestions.deleteByRunId(runId); int index = 0;
        for (SuggestionDraft draft : drafts) {
            TranscriptSegment source = current.get(draft.sourceSegmentId());
            if (source == null || source.isHumanCorrected()) { rejectedCount++; continue; }
            suggestions.save(new SpeakerCorrectionSuggestion(runId, index++, source, draft.type(), draft.targetSpeakerId(), draft.proposalDocument(),
                    draft.confidence(), draft.reason(), draft.timingSource()));
        }
        run.ready(index, rejectedCount); runs.save(run); notifySettled(run);
    }

    @Transactional
    public void fail(String runId, String code, String message) {
        runs.findById(runId).ifPresent(run -> { run.fail(code, trim(message, 1000)); runs.save(run); notifySettled(run); });
    }

    @Transactional
    public ApplyResult apply(String ownerId, String key, String runId, List<String> requestedSuggestionIds, int expectedRevision) {
        SpeakerCorrectionRun run = ownedRun(ownerId, runId); TranscriptionTask task = ownedTask(ownerId, run.getTranscriptionTaskId());
        List<String> stableSuggestionIds = requestedSuggestionIds == null ? List.of() : requestedSuggestionIds.stream().distinct().sorted().toList();
        IdempotencyRecord record = idempotency.reserve(ownerId, APPLY_OPERATION, key, Hashing.canonicalJsonHash(Map.of(
                "runId", runId, "suggestionIds", stableSuggestionIds, "revision", expectedRevision)));
        if (record.getResourceId() != null) {
            SpeakerCorrectionRun prior = ownedRun(ownerId, record.getResourceId());
            return new ApplyResult(0, 0, task.getSpeakerCorrectionRevision(), prior);
        }
        if (run.getStatus() != SpeakerCorrectionRunStatus.READY) throw new ApiException(HttpStatus.CONFLICT, "AI_CORRECTION_NOT_READY", "AI speaker corrections are not ready to apply");
        if (run.getBaseRevision() != expectedRevision || task.getSpeakerCorrectionRevision() != expectedRevision) { run.stale(); runs.save(run); throw revisionConflict(); }
        LinkedHashSet<String> ids = new LinkedHashSet<>(stableSuggestionIds);
        if (ids.isEmpty() || ids.size() > 1_000) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_CORRECTION_SIZE", "Select between 1 and 1000 AI suggestions");
        List<SpeakerCorrectionSuggestion> selected = suggestions.findByRunIdOrderBySuggestionIndex(runId).stream().filter(value -> ids.contains(value.getId())).toList();
        if (selected.size() != ids.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SUGGESTION_INVALID", "Every selected suggestion must belong to this AI correction run");
        List<TranscriptSpeakerCorrectionService.AiCorrection> changes = new ArrayList<>();
        for (SpeakerCorrectionSuggestion suggestion : selected) {
            if (suggestion.getSuggestionType() == SpeakerCorrectionSuggestionType.RELABEL) {
                changes.add(new TranscriptSpeakerCorrectionService.AiCorrection(suggestion.getSourceSegmentId(), suggestion.getSuggestionType(), suggestion.getTargetSpeakerId(), List.of()));
            } else {
                List<ProposalPart> parts;
                try { parts = mapper.readValue(suggestion.getProposalDocument(), new TypeReference<List<ProposalPart>>() { }); }
                catch (Exception exception) { throw new ApiException(HttpStatus.CONFLICT, "AI_SUGGESTION_INVALID", "Stored AI split data is invalid"); }
                changes.add(new TranscriptSpeakerCorrectionService.AiCorrection(suggestion.getSourceSegmentId(), suggestion.getSuggestionType(), null,
                        parts.stream().map(part -> new TranscriptSpeakerCorrectionService.AiFragment(part.startOffset(), part.endOffset(), part.speakerId(),
                                part.startMs(), part.endMs(), part.timingSource())).toList()));
            }
        }
        var applied = corrections.applyAi(ownerId, task.getId(), changes, expectedRevision);
        selected.forEach(SpeakerCorrectionSuggestion::markApplied); suggestions.saveAll(selected); run.applied(); runs.save(run);
        completeIdempotency(record, run.getId()); notifySettled(run);
        return new ApplyResult(applied.relabeledSegmentCount(), applied.splitSegmentCount(), applied.revision(), run);
    }

    @Transactional
    public void markQueued(String runId) { runs.findById(runId).orElseThrow(); }

    private RunDetail refreshAndDetail(SpeakerCorrectionRun run) {
        TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElse(null);
        if (task != null && (task.getTranscriptVersion() != run.getTranscriptVersion() || task.getSpeakerCorrectionRevision() != run.getBaseRevision())
                && (run.getStatus() == SpeakerCorrectionRunStatus.QUEUED || run.getStatus() == SpeakerCorrectionRunStatus.RUNNING || run.getStatus() == SpeakerCorrectionRunStatus.READY)) {
            run.stale(); runs.save(run);
        }
        return new RunDetail(RunView.from(run), suggestions.findByRunIdOrderBySuggestionIndex(run.getId()).stream().map(SuggestionView::from).toList());
    }
    private TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }
    private SpeakerCorrectionRun ownedRun(String ownerId, String runId) {
        return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SPEAKER_CORRECTION_RUN_NOT_FOUND", "AI speaker correction run was not found"));
    }
    private List<TranscriptSegment> activeSegments(TranscriptionTask task) {
        return segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion());
    }
    private static String snapshotHash(List<TranscriptSegment> source) {
        return Hashing.canonicalJsonHash(source.stream().map(value -> Map.of("id", value.getId(), "speaker", value.getEffectiveSpeakerId(),
                "source", value.getCorrectionSource().name(), "text", value.getTextContent(), "start", value.getStartMs(), "end", value.getEndMs())).toList());
    }
    private void completeIdempotency(IdempotencyRecord record, String resourceId) {
        try { idempotency.complete(record, resourceId, mapper.writeValueAsString(Map.of("id", resourceId))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent speaker correction response", exception); }
    }
    private void notifySettled(SpeakerCorrectionRun run) {
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("speaker_correction_run", run.getId(), EventType.PROGRESS_CHANGED);
        else progress.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "speaker-correction-run-settled", run.getId()));
    }
    private static ApiException revisionConflict() { return new ApiException(HttpStatus.CONFLICT, "SPEAKER_REVISION_CONFLICT", "Speaker corrections changed in another session; reload the original document and try again"); }
    private static String trim(String value, int maximum) { if (value == null) return null; return value.length() <= maximum ? value : value.substring(0, maximum); }

    public record RunWork(String runId, String ownerId, String taskId, int transcriptVersion, SceneType sceneType, String subject,
                          List<TranscriptSegment> segments, List<TranscriptSpeaker> speakers) { }
    public record SuggestionDraft(String sourceSegmentId, SpeakerCorrectionSuggestionType type, String targetSpeakerId,
                                  String proposalDocument, double confidence, String reason, SegmentTimingSource timingSource) { }
    public record ProposalPart(int startOffset, int endOffset, String speakerId, String text, long startMs, long endMs, SegmentTimingSource timingSource) { }
    public record ApplyResult(int relabeledSegmentCount, int splitSegmentCount, int revision, SpeakerCorrectionRun run) { }
    public record RunView(String id, String transcriptionTaskId, int transcriptVersion, int baseRevision, String templateVersion, String modelId,
                          String status, int suggestionCount, int rejectedCount, String failureCode, String failureMessage, java.time.Instant createdAt, java.time.Instant completedAt) {
        public static RunView from(SpeakerCorrectionRun run) { return new RunView(run.getId(), run.getTranscriptionTaskId(), run.getTranscriptVersion(), run.getBaseRevision(),
                run.getTemplateVersion(), run.getModelId(), run.getStatus().name(), run.getSuggestionCount(), run.getRejectedCount(), run.getFailureCode(),
                run.getFailureMessage(), run.getCreatedAt(), run.getCompletedAt()); }
    }
    public record SuggestionView(String id, int index, String sourceSegmentId, String type, String originalSpeakerId, long originalStartMs,
                                 long originalEndMs, String originalText, String targetSpeakerId, String proposalDocument, double confidence,
                                 String reason, boolean defaultSelected, String timingSource, boolean applied) {
        public static SuggestionView from(SpeakerCorrectionSuggestion value) { return new SuggestionView(value.getId(), value.getSuggestionIndex(), value.getSourceSegmentId(),
                value.getSuggestionType().name(), value.getOriginalSpeakerId(), value.getOriginalStartMs(), value.getOriginalEndMs(), value.getOriginalText(),
                value.getTargetSpeakerId(), value.getProposalDocument(), value.getConfidence(), value.getReason(), value.isDefaultSelected(),
                value.getTimingSource().name(), value.isApplied()); }
    }
    public record RunDetail(RunView run, List<SuggestionView> suggestions) { }
}
