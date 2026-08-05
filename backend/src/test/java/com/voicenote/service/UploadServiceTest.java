package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.AudioBlob;
import com.voicenote.domain.BlobStatus;
import com.voicenote.domain.IdempotencyRecord;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class UploadServiceTest {
    private static final String OWNER_ID = "owner-a";
    private static final String SHA256 = "a".repeat(64);

    @Test
    void reopensFailedBlobWhenTheSameAudioIsUploadedAgain() {
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        AudioBlob blob = new AudioBlob(OWNER_ID, SHA256, 3, "audio/mpeg", "meeting.mp3");
        UploadService service = new UploadService(blobs, idempotency, storage, new ObjectMapper());

        when(blobs.findById(blob.getId())).thenReturn(Optional.of(blob));
        when(blobs.findByOwnerIdAndSha256(OWNER_ID, SHA256)).thenReturn(Optional.of(blob));
        when(blobs.save(any(AudioBlob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_UNAVAILABLE", "Unable to store the audio file"))
                .when(storage).put(anyString(), any(), anyLong(), anyString());

        assertThatThrownBy(() -> service.uploadContent(OWNER_ID, blob.getId(), new ByteArrayInputStream(new byte[] {1, 2, 3})))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unable to store the audio file");
        assertThat(blob.getStatus()).isEqualTo(BlobStatus.FAILED);

        when(idempotency.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord(OWNER_ID, "CREATE_UPLOAD_INTENT", "retry-key", "request-hash"));
        UploadService.UploadIntent retry = service.createIntent(OWNER_ID, "retry-key",
                new UploadService.CreateUploadIntent(SHA256, 3, "audio/mpeg", "meeting.mp3"));

        assertThat(retry.audioBlobId()).isEqualTo(blob.getId());
        assertThat(retry.contentReady()).isFalse();
        assertThat(retry.status()).isEqualTo(BlobStatus.UPLOADING);
    }

    @Test
    void reopensStuckWritesCreatedBeforeTheWriteLeaseWasAdded() {
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AudioBlob blob = new AudioBlob(OWNER_ID, SHA256, 3, "audio/mpeg", "meeting.mp3");
        blob.claimWrite();
        ReflectionTestUtils.setField(blob, "writeStartedAt", null);
        UploadService service = new UploadService(blobs, idempotency, mock(ObjectStorage.class), new ObjectMapper());

        when(blobs.findByOwnerIdAndSha256(OWNER_ID, SHA256)).thenReturn(Optional.of(blob));
        when(blobs.save(any(AudioBlob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(idempotency.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord(OWNER_ID, "CREATE_UPLOAD_INTENT", "retry-key", "request-hash"));

        UploadService.UploadIntent retry = service.createIntent(OWNER_ID, "retry-key",
                new UploadService.CreateUploadIntent(SHA256, 3, "audio/mpeg", "meeting.mp3"));

        assertThat(retry.audioBlobId()).isEqualTo(blob.getId());
        assertThat(retry.status()).isEqualTo(BlobStatus.UPLOADING);
    }

    @Test
    void keepsAnActiveWriteLockedUntilItsLeaseExpires() {
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AudioBlob blob = new AudioBlob(OWNER_ID, SHA256, 3, "audio/mpeg", "meeting.mp3");
        blob.claimWrite();
        UploadService service = new UploadService(blobs, idempotency, mock(ObjectStorage.class), new ObjectMapper());

        when(blobs.findByOwnerIdAndSha256(OWNER_ID, SHA256)).thenReturn(Optional.of(blob));
        when(idempotency.reserve(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IdempotencyRecord(OWNER_ID, "CREATE_UPLOAD_INTENT", "retry-key", "request-hash"));

        UploadService.UploadIntent intent = service.createIntent(OWNER_ID, "retry-key",
                new UploadService.CreateUploadIntent(SHA256, 3, "audio/mpeg", "meeting.mp3"));

        assertThat(intent.status()).isEqualTo(BlobStatus.WRITING);
        verify(blobs, never()).save(any(AudioBlob.class));
    }

    @Test
    void passesTheClientImportStartTimeToThePipelineWhenCreatingTheTask() {
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        TranscriptionTaskService tasks = mock(TranscriptionTaskService.class);
        PipelineProgressService progress = mock(PipelineProgressService.class);
        AudioBlob blob = new AudioBlob(OWNER_ID, SHA256, 3, "audio/mpeg", "meeting.mp3");
        blob.claimWrite();
        blob.markReady();
        TranscriptionTask task = new TranscriptionTask(OWNER_ID, blob.getId(), SHA256, "pipeline");
        UploadService service = new UploadService(blobs, idempotency, mock(ObjectStorage.class), new ObjectMapper(), tasks, progress);
        Instant importStartedAt = Instant.now().minusSeconds(9);

        when(blobs.findById(blob.getId())).thenReturn(Optional.of(blob));
        when(tasks.create(eq(OWNER_ID), eq("complete-key"), any(TranscriptionTaskService.CreateTaskCommand.class), any(Instant.class))).thenReturn(task);

        service.complete(OWNER_ID, "complete-key", blob.getId(), null, importStartedAt);

        ArgumentCaptor<Instant> captured = ArgumentCaptor.forClass(Instant.class);
        verify(tasks).create(eq(OWNER_ID), eq("complete-key"),
                eq(new TranscriptionTaskService.CreateTaskCommand(blob.getId(), null)), captured.capture());
        assertThat(captured.getValue()).isEqualTo(importStartedAt);
    }
}
