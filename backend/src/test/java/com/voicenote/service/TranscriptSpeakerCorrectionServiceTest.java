package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TranscriptSpeakerCorrectionServiceTest {
    private final TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptSpeakerRepository speakers = mock(TranscriptSpeakerRepository.class);
    private final OrganizedDocumentRepository organizedDocuments = mock(OrganizedDocumentRepository.class);
    private final OrganizationInvocationRepository organizationInvocations = mock(OrganizationInvocationRepository.class);
    private final AnalysisRunRepository analyses = mock(AnalysisRunRepository.class);
    private final KnowledgeDocumentRepository knowledgeDocuments = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeIndexVersionRepository indexVersions = mock(KnowledgeIndexVersionRepository.class);
    private final PipelineProgressService pipeline = mock(PipelineProgressService.class);
    private final TranscriptSpeakerCorrectionService service = new TranscriptSpeakerCorrectionService(tasks, segments, speakers,
            organizedDocuments, organizationInvocations, analyses, knowledgeDocuments, indexVersions, pipeline);

    @BeforeEach
    void defaultDerivedContent() {
        when(organizedDocuments.findByTranscriptionTaskId(any())).thenReturn(List.of());
        when(analyses.findByTranscriptionTaskId(any())).thenReturn(List.of());
        when(knowledgeDocuments.findByTranscriptionTaskId(any())).thenReturn(List.of());
    }

    @Test
    void correctsAndRestoresASelectedSegmentWithoutChangingTheAsrIdentity() {
        TranscriptionTask task = readyTask();
        TranscriptSegment segment = new TranscriptSegment(task.getId(), task.getTranscriptVersion(), 0, "SPEAKER_0", 0, 1_000, "hello");
        stubTaskAndSegment(task, segment);
        when(speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(task.getId(), task.getTranscriptVersion(), "SPEAKER_1"))
                .thenReturn(Optional.of(new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_1")));
        when(pipeline.invalidateForSpeakerCorrection(task.getId())).thenReturn(1, 2);

        var corrected = service.correct("owner", task.getId(), List.of(segment.getId()), "SPEAKER_1", 0);

        assertThat(corrected.changedSegmentCount()).isEqualTo(1);
        assertThat(segment.getAsrSpeakerId()).isEqualTo("SPEAKER_0");
        assertThat(segment.getEffectiveSpeakerId()).isEqualTo("SPEAKER_1");
        assertThat(segment.isSpeakerCorrected()).isTrue();

        var restored = service.correct("owner", task.getId(), List.of(segment.getId()), null, 0);

        assertThat(restored.changedSegmentCount()).isEqualTo(1);
        assertThat(segment.getEffectiveSpeakerId()).isEqualTo("SPEAKER_0");
        assertThat(segment.isSpeakerCorrected()).isFalse();
        verify(segments, times(2)).saveAll(any());
    }

    @Test
    void rejectsAStaleClientRevisionBeforeChangingSegments() {
        TranscriptionTask task = readyTask();
        task.speakerCorrectionApplied();
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.correct("owner", task.getId(), List.of("segment"), "SPEAKER_1", 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reload the original document");
        verifyNoInteractions(segments);
    }

    @Test
    void invalidatesDerivedDocumentsAndRetainsTheRetiredIndexPointerForExternalCleanup() {
        TranscriptionTask task = readyTask();
        TranscriptSegment segment = new TranscriptSegment(task.getId(), task.getTranscriptVersion(), 0, "SPEAKER_0", 0, 1_000, "hello");
        stubTaskAndSegment(task, segment);
        when(speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(task.getId(), task.getTranscriptVersion(), "SPEAKER_1"))
                .thenReturn(Optional.of(new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_1")));

        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "title");
        organized.queue(); organized.begin(); organized.ready("{}", "text");
        AnalysisRun summary = new AnalysisRun("owner", task.getId(), "hash", "summary", "goal", "v1", "model", "semantic", 3);
        summary.useOrganizedDocument(organized.getId()); summary.start(); summary.succeed("{}", "reviewed");
        KnowledgeDocument knowledge = new KnowledgeDocument("owner", task.getId(), task.getTranscriptVersion(), "title", organized.getId(), 1);
        KnowledgeIndexVersion index = new KnowledgeIndexVersion(knowledge.getId(), 1, organized.getId(), 1, "config");
        index.ready(); index.activate(); knowledge.activateIndexVersion(index.getId());
        when(organizedDocuments.findByTranscriptionTaskId(task.getId())).thenReturn(List.of(organized));
        when(analyses.findByTranscriptionTaskId(task.getId())).thenReturn(List.of(summary));
        when(knowledgeDocuments.findByTranscriptionTaskId(task.getId())).thenReturn(List.of(knowledge));
        when(indexVersions.findByKnowledgeDocumentIdOrderByGenerationDesc(knowledge.getId())).thenReturn(List.of(index));
        when(pipeline.invalidateForSpeakerCorrection(task.getId())).thenReturn(1);

        service.correct("owner", task.getId(), List.of(segment.getId()), "SPEAKER_1", 0);

        assertThat(organized.getStatus()).isEqualTo(OrganizedDocumentStatus.STALE);
        assertThat(summary.getStatus()).isEqualTo(AnalysisRunStatus.STALE);
        assertThat(knowledge.getStatus()).isEqualTo(KnowledgeDocumentStatus.STALE);
        assertThat(knowledge.getActiveIndexVersionId()).isEqualTo(index.getId());
        assertThat(index.getStatus()).isEqualTo(KnowledgeIndexVersionStatus.RETIRED);
        assertThat(index.isActive()).isFalse();
        verify(organizationInvocations).deleteByOrganizedDocumentId(organized.getId());
    }

    @Test
    void rejectsCorrectionsWhileFormalDocumentGenerationIsRunning() {
        TranscriptionTask task = readyTask();
        TranscriptSegment segment = new TranscriptSegment(task.getId(), task.getTranscriptVersion(), 0, "SPEAKER_0", 0, 1_000, "hello");
        stubTaskAndSegment(task, segment);
        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "title");
        organized.queue();
        when(organizedDocuments.findByTranscriptionTaskId(task.getId())).thenReturn(List.of(organized));

        assertThatThrownBy(() -> service.correct("owner", task.getId(), List.of(segment.getId()), null, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("finish before correcting speakers");
        verify(segments, never()).saveAll(any());
    }

    @Test
    void appliesAnAiSplitWithoutDeletingTheAsrSegment() {
        TranscriptionTask task = readyTask();
        TranscriptSegment segment = new TranscriptSegment(task.getId(), task.getTranscriptVersion(), 0, "SPEAKER_0", 0, 2_000, "你好我来回答");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(segments.findAllById(any())).thenReturn(List.of(segment));
        when(speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(task.getId(), task.getTranscriptVersion())).thenReturn(List.of(
                new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_0"),
                new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_1")));
        when(pipeline.invalidateForSpeakerCorrection(task.getId())).thenReturn(1);

        var result = service.applyAi("owner", task.getId(), List.of(new TranscriptSpeakerCorrectionService.AiCorrection(segment.getId(),
                SpeakerCorrectionSuggestionType.SPLIT, null, List.of(
                new TranscriptSpeakerCorrectionService.AiFragment(0, 2, "SPEAKER_0", 0, 700, SegmentTimingSource.WORD_ALIGNED),
                new TranscriptSpeakerCorrectionService.AiFragment(2, 6, "SPEAKER_1", 800, 2_000, SegmentTimingSource.WORD_ALIGNED)
        ))), 0);

        assertThat(result.splitSegmentCount()).isEqualTo(1);
        assertThat(segment.isActive()).isFalse();
        var saved = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(segments).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(3);
        assertThat(((TranscriptSegment) saved.getValue().get(1)).getTextContent()).isEqualTo("你好");
        assertThat(((TranscriptSegment) saved.getValue().get(2)).getEffectiveSpeakerId()).isEqualTo("SPEAKER_1");
    }

    @Test
    void refusesToOverwriteAHumanCorrectionWithAi() {
        TranscriptionTask task = readyTask();
        TranscriptSegment segment = new TranscriptSegment(task.getId(), task.getTranscriptVersion(), 0, "SPEAKER_0", 0, 1_000, "hello");
        segment.correctSpeaker("SPEAKER_1");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(segments.findAllById(any())).thenReturn(List.of(segment));
        when(speakers.findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(task.getId(), task.getTranscriptVersion())).thenReturn(List.of(
                new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_0"),
                new TranscriptSpeaker(task.getId(), task.getTranscriptVersion(), "SPEAKER_1")));

        assertThatThrownBy(() -> service.applyAi("owner", task.getId(), List.of(new TranscriptSpeakerCorrectionService.AiCorrection(
                segment.getId(), SpeakerCorrectionSuggestionType.RELABEL, "SPEAKER_0", List.of())), 0))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot overwrite");
        verify(segments, never()).saveAll(any());
    }

    private TranscriptionTask readyTask() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.nextTranscriptVersion(); task.transcriptPersisted(); task.awaitFormalDocument();
        return task;
    }

    private void stubTaskAndSegment(TranscriptionTask task, TranscriptSegment segment) {
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(segments.findAllById(any())).thenReturn(List.of(segment));
    }
}
