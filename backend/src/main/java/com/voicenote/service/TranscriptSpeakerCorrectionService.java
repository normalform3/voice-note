package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies human speaker overrides while preserving the provider's original diarization result. */
@Service
public class TranscriptSpeakerCorrectionService {
    private final TranscriptionTaskRepository tasks;
    private final TranscriptSegmentRepository segments;
    private final TranscriptSpeakerRepository speakers;
    private final OrganizedDocumentRepository organizedDocuments;
    private final OrganizationInvocationRepository organizationInvocations;
    private final AnalysisRunRepository analyses;
    private final KnowledgeDocumentRepository knowledgeDocuments;
    private final KnowledgeIndexVersionRepository indexVersions;
    private final PipelineProgressService pipeline;

    public TranscriptSpeakerCorrectionService(TranscriptionTaskRepository tasks, TranscriptSegmentRepository segments,
                                              TranscriptSpeakerRepository speakers, OrganizedDocumentRepository organizedDocuments,
                                              OrganizationInvocationRepository organizationInvocations, AnalysisRunRepository analyses,
                                              KnowledgeDocumentRepository knowledgeDocuments, KnowledgeIndexVersionRepository indexVersions,
                                              PipelineProgressService pipeline) {
        this.tasks = tasks; this.segments = segments; this.speakers = speakers; this.organizedDocuments = organizedDocuments;
        this.organizationInvocations = organizationInvocations; this.analyses = analyses; this.knowledgeDocuments = knowledgeDocuments;
        this.indexVersions = indexVersions; this.pipeline = pipeline;
    }

    @Transactional
    public CorrectionResult correct(String ownerId, String taskId, List<String> requestedSegmentIds, String requestedSpeakerId, int expectedRevision) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (!task.isTranscriptReady()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Wait for the original document before correcting speakers");
        }
        if (task.getSpeakerCorrectionRevision() != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_REVISION_CONFLICT", "Speaker corrections changed in another session; reload the original document and try again");
        }

        LinkedHashSet<String> segmentIds = new LinkedHashSet<>(requestedSegmentIds == null ? List.of() : requestedSegmentIds);
        if (segmentIds.isEmpty() || segmentIds.size() > 1_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SPEAKER_CORRECTION_SIZE", "Select between 1 and 1000 transcript segments");
        }
        String speakerId = requestedSpeakerId == null ? null : requestedSpeakerId.trim();
        if (speakerId != null && speakerId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SPEAKER_ID_INVALID", "speakerId must be a recognized speaker or null");
        }
        if (speakerId != null && speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(taskId, task.getTranscriptVersion(), speakerId).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SPEAKER_NOT_FOUND", "The target speaker was not found in the current transcript");
        }

        rejectWhileDerivedContentIsRunning(taskId);
        List<TranscriptSegment> selected = segments.findAllById(segmentIds);
        Set<String> found = selected.stream()
                .filter(value -> value.isActive() && value.getTranscriptionTaskId().equals(taskId) && value.getTranscriptVersion() == task.getTranscriptVersion())
                .map(TranscriptSegment::getId).collect(java.util.stream.Collectors.toSet());
        if (found.size() != segmentIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRANSCRIPT_SEGMENT_INVALID", "Every selected segment must belong to the current transcript version");
        }

        int changed = 0;
        for (TranscriptSegment segment : selected) if (segment.correctSpeaker(speakerId)) changed++;
        if (changed == 0) return new CorrectionResult(0, task.getSpeakerCorrectionRevision());
        segments.saveAll(selected);
        invalidateDerivedContent(taskId);
        int revision = pipeline.invalidateForSpeakerCorrection(taskId);
        return new CorrectionResult(changed, revision);
    }

    @Transactional
    public AiCorrectionResult applyAi(String ownerId, String taskId, List<AiCorrection> corrections, int expectedRevision) {
        TranscriptionTask task = tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (!task.isTranscriptReady()) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Wait for the original document before correcting speakers");
        if (task.getSpeakerCorrectionRevision() != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_REVISION_CONFLICT", "Speaker corrections changed in another session; reload the original document and try again");
        }
        if (corrections == null || corrections.isEmpty() || corrections.size() > 1_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_CORRECTION_SIZE", "Select between 1 and 1000 AI suggestions");
        }
        rejectWhileDerivedContentIsRunning(taskId);
        Set<String> requestedIds = corrections.stream().map(AiCorrection::segmentId).collect(java.util.stream.Collectors.toSet());
        if (requestedIds.size() != corrections.size()) throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_AI_CORRECTION", "Only one AI suggestion may be applied to each segment");
        Map<String, TranscriptSegment> selected = segments.findAllById(requestedIds).stream().collect(java.util.stream.Collectors.toMap(TranscriptSegment::getId, value -> value));
        Set<String> knownSpeakers = speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(taskId, task.getTranscriptVersion()).stream()
                .map(TranscriptSpeaker::getAsrSpeakerId).collect(java.util.stream.Collectors.toSet());
        List<TranscriptSegment> changedSegments = new java.util.ArrayList<>();
        int relabeled = 0; int split = 0;
        for (AiCorrection correction : corrections) {
            TranscriptSegment source = selected.get(correction.segmentId());
            if (source == null || !source.isActive() || source.getTranscriptVersion() != task.getTranscriptVersion()
                    || !source.getTranscriptionTaskId().equals(taskId)) {
                throw new ApiException(HttpStatus.CONFLICT, "AI_CORRECTION_SOURCE_STALE", "An AI suggestion no longer refers to an active transcript segment");
            }
            if (source.isHumanCorrected()) throw new ApiException(HttpStatus.CONFLICT, "HUMAN_CORRECTION_PROTECTED", "AI cannot overwrite a human speaker correction");
            if (correction.type() == SpeakerCorrectionSuggestionType.RELABEL) {
                if (!knownSpeakers.contains(correction.targetSpeakerId())) throw new ApiException(HttpStatus.BAD_REQUEST, "SPEAKER_NOT_FOUND", "The AI target speaker was not found");
                if (source.correctSpeakerByAi(correction.targetSpeakerId())) { changedSegments.add(source); relabeled++; }
                continue;
            }
            List<AiFragment> fragments = correction.fragments() == null ? List.of() : correction.fragments();
            if (fragments.size() < 2) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SPLIT_INVALID", "An AI split must contain at least two fragments");
            int cursor = 0; long previousEnd = source.getStartMs();
            List<TranscriptSegment> created = new java.util.ArrayList<>();
            for (AiFragment fragment : fragments) {
                if (!knownSpeakers.contains(fragment.speakerId()) || fragment.startOffset() != cursor || fragment.endOffset() <= fragment.startOffset()
                        || fragment.endOffset() > source.getTextContent().length() || fragment.startMs() < source.getStartMs()
                        || fragment.endMs() < fragment.startMs() || fragment.endMs() > source.getEndMs() || fragment.startMs() < previousEnd) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SPLIT_INVALID", "AI split fragments must cover the original text and ordered time range");
                }
                created.add(TranscriptSegment.aiFragment(source, fragment.startOffset(), fragment.endOffset(), fragment.speakerId(),
                        fragment.startMs(), fragment.endMs(), fragment.timingSource()));
                cursor = fragment.endOffset(); previousEnd = fragment.endMs();
            }
            if (cursor != source.getTextContent().length()) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_SPLIT_INVALID", "AI split fragments must cover the original text exactly");
            source.deactivateForAiSplit(); changedSegments.add(source); changedSegments.addAll(created); split++;
        }
        if (relabeled == 0 && split == 0) return new AiCorrectionResult(0, 0, task.getSpeakerCorrectionRevision());
        segments.saveAll(changedSegments); invalidateDerivedContent(taskId);
        int revision = pipeline.invalidateForSpeakerCorrection(taskId);
        return new AiCorrectionResult(relabeled, split, revision);
    }

    private void rejectWhileDerivedContentIsRunning(String taskId) {
        boolean documentRunning = organizedDocuments.findByTranscriptionTaskId(taskId).stream()
                .anyMatch(value -> value.getStatus() == OrganizedDocumentStatus.PENDING || value.getStatus() == OrganizedDocumentStatus.QUEUED || value.getStatus() == OrganizedDocumentStatus.ORGANIZING);
        boolean indexRunning = knowledgeDocuments.findByTranscriptionTaskId(taskId).stream()
                .anyMatch(value -> value.getStatus() == KnowledgeDocumentStatus.PENDING || value.getStatus() == KnowledgeDocumentStatus.QUEUED || value.getStatus() == KnowledgeDocumentStatus.INDEXING);
        boolean summaryRunning = analyses.findByTranscriptionTaskId(taskId).stream()
                .anyMatch(value -> value.getOrganizedDocumentId() != null && (value.getStatus() == AnalysisRunStatus.QUEUED || value.getStatus() == AnalysisRunStatus.RUNNING));
        if (documentRunning || indexRunning || summaryRunning) {
            throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_EDIT_IN_PROGRESS", "Wait for formal-document, summary, or knowledge-index processing to finish before correcting speakers");
        }
    }

    private void invalidateDerivedContent(String taskId) {
        for (OrganizedDocument document : organizedDocuments.findByTranscriptionTaskId(taskId)) {
            document.stale(); organizedDocuments.save(document);
            organizationInvocations.deleteByOrganizedDocumentId(document.getId());
        }
        for (AnalysisRun run : analyses.findByTranscriptionTaskId(taskId)) {
            if (run.getOrganizedDocumentId() != null) { run.stale(); analyses.save(run); }
        }
        for (KnowledgeDocument document : knowledgeDocuments.findByTranscriptionTaskId(taskId)) {
            for (KnowledgeIndexVersion version : indexVersions.findByKnowledgeDocumentIdOrderByGenerationDesc(document.getId())) {
                if (version.isActive()) { version.retire(); indexVersions.save(version); }
            }
            document.stale(); knowledgeDocuments.save(document);
        }
    }

    public record CorrectionResult(int changedSegmentCount, int revision) { }
    public record AiCorrection(String segmentId, SpeakerCorrectionSuggestionType type, String targetSpeakerId, List<AiFragment> fragments) { }
    public record AiFragment(int startOffset, int endOffset, String speakerId, long startMs, long endMs, SegmentTimingSource timingSource) { }
    public record AiCorrectionResult(int relabeledSegmentCount, int splitSegmentCount, int revision) { }
}
