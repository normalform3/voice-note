package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpeakerCorrectionServiceTest {
    private final SpeakerCorrectionRunRepository runs = mock(SpeakerCorrectionRunRepository.class);
    private final SpeakerCorrectionSuggestionRepository suggestions = mock(SpeakerCorrectionSuggestionRepository.class);
    private final SpeakerCorrectionInvocationRepository invocations = mock(SpeakerCorrectionInvocationRepository.class);
    private final TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptSpeakerRepository speakers = mock(TranscriptSpeakerRepository.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final AppProperties properties = new AppProperties();
    private final ProgressEventPublisher progress = mock(ProgressEventPublisher.class);
    private final TranscriptSpeakerCorrectionService corrections = mock(TranscriptSpeakerCorrectionService.class);
    private final SpeakerCorrectionService service;

    SpeakerCorrectionServiceTest() {
        properties.getDashscope().setChatModel("qwen-test");
        service = new SpeakerCorrectionService(runs, suggestions, invocations, tasks, segments, speakers, idempotency, outbox,
                new ObjectMapper(), properties, progress, corrections);
    }

    @BeforeEach
    void defaults() { when(suggestions.findByRunIdOrderBySuggestionIndex(anyString())).thenReturn(List.of()); }

    @Test
    void createsAnIdempotentAsynchronousRunForTheCurrentRevision() {
        TranscriptionTask task = readyTask(); TranscriptSegment segment = new TranscriptSegment(task.getId(), 1, 0, "SPEAKER_0", 0, 1_000, "原文");
        IdempotencyRecord record = new IdempotencyRecord("owner", "CREATE_SPEAKER_CORRECTION_RUN", "key", "hash");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), 1)).thenReturn(List.of(segment));
        when(idempotency.reserve(eq("owner"), anyString(), eq("key"), anyString())).thenReturn(record);
        when(runs.findTopByOwnerIdAndTranscriptionTaskIdOrderByCreatedAtDesc("owner", task.getId())).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SpeakerCorrectionRun run = service.create("owner", "key", task.getId(), 0);

        assertThat(run.getStatus()).isEqualTo(SpeakerCorrectionRunStatus.QUEUED);
        assertThat(run.getModelId()).isEqualTo("qwen-test");
        verify(outbox).enqueue("speaker_correction_run", run.getId(), EventType.SPEAKER_CORRECTION_REQUESTED);
        verify(idempotency).complete(eq(record), eq(run.getId()), anyString());
    }

    @Test
    void marksAReadyPreviewStaleWhenTheHumanRevisionChanges() {
        TranscriptionTask task = readyTask();
        SpeakerCorrectionRun run = new SpeakerCorrectionRun("owner", task.getId(), 1, 0, "a".repeat(64), "speaker-correction-v1", "qwen-test");
        run.start(); run.ready(1, 0); task.speakerCorrectionApplied();
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));

        var detail = service.detail("owner", run.getId());

        assertThat(detail.run().status()).isEqualTo("STALE");
        verify(runs).save(run);
    }

    private static TranscriptionTask readyTask() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        task.nextTranscriptVersion(); task.transcriptPersisted(); task.awaitFormalDocument(); return task;
    }
}
