package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.AudioBlob;
import com.voicenote.domain.IdempotencyRecord;
import com.voicenote.domain.SceneType;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.repository.TaskAttemptRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptionTaskServiceTest {
    @Test
    void preservesAnExplicitChoiceToDisableSpeakerDiarization() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), false, null);

        assertThat(config.normalized().diarizationEnabled()).isFalse();
    }

    @Test
    void keepsSpeakerDiarizationEnabledForLegacyRequestsWithoutTheField() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), null, null);

        assertThat(config.normalized().diarizationEnabled()).isTrue();
    }

    @Test
    void rejectsSpeakerCountsOutsideTheProviderRange() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), true, 1);

        assertThatThrownBy(config::normalized)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("speakerCount must be between 2 and 100");
    }

    @Test
    void rejectsSpeakerCountWhenDiarizationIsDisabled() {
        TranscriptionTaskService.AsrConfig config = new TranscriptionTaskService.AsrConfig(List.of("zh"), false, 2);

        assertThatThrownBy(config::normalized)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("requires speaker diarization");
    }

    @Test
    void hotwordRevisionChangesTheSemanticTaskHash() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        HotwordLibraryService hotwords = mock(HotwordLibraryService.class);
        PipelineProgressService pipeline = mock(PipelineProgressService.class);
        OutboxService outbox = mock(OutboxService.class);
        TranscriptionTaskService service = new TranscriptionTaskService(tasks, mock(TaskAttemptRepository.class), blobs,
                idempotency, outbox, new ObjectMapper(), pipeline, mock(KnowledgeDocumentService.class),
                mock(DocumentOrganizationService.class), mock(KnowledgeVectorStore.class), hotwords);
        AudioBlob blob = AudioBlob.readyRecording("audio-1", "owner", "a".repeat(64), 42, "audio/webm", "sample.webm");
        when(blobs.findById(blob.getId())).thenReturn(Optional.of(blob));
        when(tasks.findByOwnerIdAndAudioBlobIdAndAsrConfigHashAndPipelineVersion(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(idempotency.reserve(eq("owner"), anyString(), anyString(), anyString())).thenAnswer(call ->
                new IdempotencyRecord("owner", "CREATE_TRANSCRIPTION_TASK", call.getArgument(2), call.getArgument(3)));
        when(hotwords.resolveForUse("owner", "library-1")).thenReturn(
                new HotwordLibraryService.Selection("library-1", 1, "vocab-1"),
                new HotwordLibraryService.Selection("library-1", 2, "vocab-1"));
        var command = new TranscriptionTaskService.CreateTaskCommand(blob.getId(),
                new TranscriptionTaskService.AsrConfig(List.of("zh", "en"), true, null, "library-1"));

        TranscriptionTask first = service.create("owner", "key-1", command);
        TranscriptionTask second = service.create("owner", "key-2", command);

        ArgumentCaptor<String> configHashes = ArgumentCaptor.forClass(String.class);
        verify(tasks, times(2)).findByOwnerIdAndAudioBlobIdAndAsrConfigHashAndPipelineVersion(
                eq("owner"), eq(blob.getId()), configHashes.capture(), anyString());
        assertThat(configHashes.getAllValues().get(0)).isNotEqualTo(configHashes.getAllValues().get(1));
        assertThat(first.getHotwordLibraryRevision()).isEqualTo(1);
        assertThat(second.getHotwordLibraryRevision()).isEqualTo(2);
    }

    @Test
    void rebuildsKnowledgeOnlyWhenRetrievalContextMetadataChanges() {
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        KnowledgeDocumentService knowledge = mock(KnowledgeDocumentService.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "hash", "{}", "pipeline");
        Instant occurredAt = Instant.parse("2026-09-07T00:00:00Z");
        task.updateMetadata(occurredAt, SceneType.OTHER, null, "[]");
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(tasks.save(task)).thenReturn(task);
        TranscriptionTaskService service = new TranscriptionTaskService(tasks, mock(TaskAttemptRepository.class), mock(AudioBlobRepository.class),
                mock(IdempotencyService.class), mock(OutboxService.class), new ObjectMapper(), mock(PipelineProgressService.class), knowledge,
                mock(DocumentOrganizationService.class), mock(KnowledgeVectorStore.class), mock(HotwordLibraryService.class));

        service.updateMetadata("owner", task.getId(), occurredAt, SceneType.OTHER, null, List.of("tag-only"));
        verifyNoInteractions(knowledge);

        service.updateMetadata("owner", task.getId(), occurredAt, SceneType.MEETING, "roadmap", List.of("tag-only"));
        verify(knowledge).refreshForMetadata("owner", task.getId());
    }
}
