package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
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
                .filter(value -> value.getTranscriptionTaskId().equals(taskId) && value.getTranscriptVersion() == task.getTranscriptVersion())
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
}
