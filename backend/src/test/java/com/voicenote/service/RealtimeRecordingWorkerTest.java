package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.repository.RealtimeRecordingPartRepository;
import com.voicenote.repository.RealtimeRecordingSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeRecordingWorkerTest {
    @Test
    void suspendsTheConsumerTransactionBeforeStartingFinalization() throws Exception {
        Transactional transactional = RealtimeRecordingWorker.class.getMethod("process", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void mergesPartsByteForByteAndCreatesTheCanonicalBatchTranscriptionTask() throws Exception {
        RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
        RealtimeRecordingPartRepository parts = mock(RealtimeRecordingPartRepository.class);
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        TranscriptionTaskService tasks = mock(TranscriptionTaskService.class);
        ProgressEventPublisher progress = mock(ProgressEventPublisher.class);
        RealtimeRecordingSession session = finalizingSession();
        List<RealtimeRecordingPart> values = List.of(
                new RealtimeRecordingPart(session.getId(), 0, "part-0", 3, "a".repeat(64)),
                new RealtimeRecordingPart(session.getId(), 1, "part-1", 2, "b".repeat(64)));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.claimFinalization(eq(session.getId()), any(Instant.class))).thenAnswer(call -> { session.beginProcessing(); return 1; });
        when(parts.findBySessionIdOrderByPartNumber(session.getId())).thenReturn(values);
        when(storage.sizeIfExists(anyString())).thenReturn(java.util.OptionalLong.empty());
        when(storage.get("part-0")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(storage.get("part-1")).thenReturn(new ByteArrayInputStream(new byte[] {4, 5}));
        AtomicReference<byte[]> completeAudio = new AtomicReference<>();
        doAnswer(call -> { completeAudio.set(call.<java.io.InputStream>getArgument(1).readAllBytes()); return null; })
                .when(storage).put(anyString(), any(), eq(5L), eq("audio/webm;codecs=opus"));
        when(blobs.findByOwnerIdAndSha256(eq("owner"), anyString())).thenReturn(Optional.empty());
        when(blobs.save(any())).thenAnswer(call -> call.getArgument(0));
        TranscriptionTask task = new TranscriptionTask("owner", session.getId(), "pipeline", "provider");
        when(tasks.create(eq("owner"), eq("realtime-final-" + session.getId()), any(), any(Instant.class))).thenReturn(task);
        when(tasks.updateMetadata(eq("owner"), eq(task.getId()), eq(session.getStartedAt()), eq(SceneType.OTHER), isNull(), eq(List.of()))).thenReturn(task);
        RealtimeRecordingWorker worker = new RealtimeRecordingWorker(sessions, parts, blobs, storage, tasks, progress, new ObjectMapper(), enabledProperties());

        worker.process(session.getId());

        assertThat(completeAudio.get()).containsExactly(1, 2, 3, 4, 5);
        InOrder storageOrder = inOrder(storage);
        storageOrder.verify(storage).get("part-0");
        storageOrder.verify(storage).get("part-1");
        storageOrder.verify(storage).put(anyString(), any(), eq(5L), eq("audio/webm;codecs=opus"));
        assertThat(session.getStatus()).isEqualTo(RealtimeRecordingStatus.READY);
        assertThat(session.getTranscriptionTaskId()).isEqualTo(task.getId());
        ArgumentCaptor<AudioBlob> blob = ArgumentCaptor.forClass(AudioBlob.class);
        verify(blobs).save(blob.capture());
        assertThat(blob.getValue().getSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new byte[] {1, 2, 3, 4, 5})));
        verify(parts).deleteBySessionId(session.getId());
        verify(storage).removeQuietly("part-0");
        verify(storage).removeQuietly("part-1");
    }

    @Test
    void keepsTemporaryPartsWhenFinalObjectWriteFails() {
        RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
        RealtimeRecordingPartRepository parts = mock(RealtimeRecordingPartRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        RealtimeRecordingSession session = finalizingSession();
        List<RealtimeRecordingPart> values = List.of(
                new RealtimeRecordingPart(session.getId(), 0, "part-0", 3, "a".repeat(64)),
                new RealtimeRecordingPart(session.getId(), 1, "part-1", 2, "b".repeat(64)));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(sessions.claimFinalization(eq(session.getId()), any(Instant.class))).thenAnswer(call -> { session.beginProcessing(); return 1; });
        when(parts.findBySessionIdOrderByPartNumber(session.getId())).thenReturn(values);
        when(storage.sizeIfExists(anyString())).thenReturn(java.util.OptionalLong.empty());
        when(storage.get("part-0")).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(storage.get("part-1")).thenReturn(new ByteArrayInputStream(new byte[] {4, 5}));
        doThrow(new IllegalStateException("minio unavailable")).when(storage).put(anyString(), any(), anyLong(), anyString());
        RealtimeRecordingWorker worker = new RealtimeRecordingWorker(sessions, parts, mock(AudioBlobRepository.class), storage,
                mock(TranscriptionTaskService.class), mock(ProgressEventPublisher.class), new ObjectMapper(), enabledProperties());

        worker.process(session.getId());

        assertThat(session.getStatus()).isEqualTo(RealtimeRecordingStatus.FAILED);
        verify(parts, never()).deleteBySessionId(anyString());
        verify(storage, never()).removeQuietly("part-0");
    }

    @Test
    void retriesAStaleFinalizingSessionDuringRecovery() {
        RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
        RealtimeRecordingWorker worker = new RealtimeRecordingWorker(sessions, mock(RealtimeRecordingPartRepository.class),
                mock(AudioBlobRepository.class), mock(ObjectStorage.class), mock(TranscriptionTaskService.class),
                mock(ProgressEventPublisher.class), new ObjectMapper(), enabledProperties());
        RealtimeRecordingSession session = finalizingSession();
        when(sessions.findTop20ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any(Instant.class))).thenReturn(List.of(session));
        when(sessions.claimFinalization(eq(session.getId()), any(Instant.class))).thenAnswer(call -> { session.beginProcessing(); return 1; });
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        worker.recoverStrandedFinalizations();

        verify(sessions).findById(session.getId());
        assertThat(session.getStatus()).isEqualTo(RealtimeRecordingStatus.FAILED);
    }

    @Test
    void ignoresASecondWorkerWhenTheDatabaseClaimIsAlreadyHeld() {
        RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
        RealtimeRecordingPartRepository parts = mock(RealtimeRecordingPartRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        RealtimeRecordingWorker worker = new RealtimeRecordingWorker(sessions, parts, mock(AudioBlobRepository.class), storage,
                mock(TranscriptionTaskService.class), mock(ProgressEventPublisher.class), new ObjectMapper(), enabledProperties());
        RealtimeRecordingSession session = finalizingSession();
        when(sessions.claimFinalization(eq(session.getId()), any(Instant.class))).thenReturn(0);

        worker.process(session.getId());

        verify(sessions, never()).findById(session.getId());
        verifyNoInteractions(parts, storage);
    }

    @Test
    void resumesFromAnExistingCompleteObjectAfterAnotherWorkerCleanedTheParts() {
        RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
        RealtimeRecordingPartRepository parts = mock(RealtimeRecordingPartRepository.class);
        AudioBlobRepository blobs = mock(AudioBlobRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        TranscriptionTaskService tasks = mock(TranscriptionTaskService.class);
        RealtimeRecordingSession session = finalizingHotwordSession();
        String finalKey = "owners/owner/audio/" + session.getId() + "/source";
        when(sessions.claimFinalization(eq(session.getId()), any(Instant.class))).thenAnswer(call -> { session.beginProcessing(); return 1; });
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(parts.findBySessionIdOrderByPartNumber(session.getId())).thenReturn(List.of());
        when(storage.sizeIfExists(finalKey)).thenReturn(java.util.OptionalLong.of(5));
        when(storage.get(finalKey)).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}));
        when(blobs.findByOwnerIdAndSha256(eq("owner"), anyString())).thenReturn(Optional.empty());
        when(blobs.save(any())).thenAnswer(call -> call.getArgument(0));
        TranscriptionTask task = new TranscriptionTask("owner", session.getId(), "pipeline", "provider");
        when(tasks.createResolved(eq("owner"), eq("realtime-final-" + session.getId()), eq(session.getId()), any(), any(Instant.class))).thenReturn(task);
        when(tasks.updateMetadata(eq("owner"), eq(task.getId()), eq(session.getStartedAt()), eq(SceneType.OTHER), isNull(), eq(List.of()))).thenReturn(task);
        RealtimeRecordingWorker worker = new RealtimeRecordingWorker(sessions, parts, blobs, storage, tasks,
                mock(ProgressEventPublisher.class), new ObjectMapper(), enabledProperties());

        worker.process(session.getId());

        assertThat(session.getStatus()).isEqualTo(RealtimeRecordingStatus.READY);
        verify(tasks).createResolved(eq("owner"), eq("realtime-final-" + session.getId()), eq(session.getId()),
                argThat(config -> "library-1".equals(config.hotwordLibraryId())
                        && Integer.valueOf(3).equals(config.hotwordLibraryRevision())
                        && "vocab-1".equals(config.vocabularyId())), any(Instant.class));
        verify(storage, never()).get("part-0");
        verify(storage, never()).put(anyString(), any(), anyLong(), anyString());
    }

    private static RealtimeRecordingSession finalizingSession() {
        RealtimeRecordingSession session = new RealtimeRecordingSession("owner", "audio/webm;codecs=opus", "recording.webm", 48_000,
                "[\"zh\",\"en\"]", "{\"languageHints\":[\"zh\",\"en\"],\"diarizationEnabled\":true}", Instant.now().minusSeconds(30));
        session.partReceived(0, 3);
        session.partReceived(1, 2);
        session.beginFinalizing(2);
        return session;
    }

    private static RealtimeRecordingSession finalizingHotwordSession() {
        RealtimeRecordingSession session = new RealtimeRecordingSession("owner", "audio/webm;codecs=opus", "recording.webm", 48_000,
                "[\"zh\",\"en\"]", "{\"languageHints\":[\"en\",\"zh\"],\"diarizationEnabled\":true,\"hotwordLibraryId\":\"library-1\",\"hotwordLibraryRevision\":3,\"vocabularyId\":\"vocab-1\"}",
                Instant.now().minusSeconds(30));
        session.attachHotword("library-1", 3);
        session.partReceived(0, 3);
        session.partReceived(1, 2);
        session.beginFinalizing(2);
        return session;
    }

    private static AppProperties enabledProperties() {
        AppProperties properties = new AppProperties();
        properties.getWorkers().setEnabled(true);
        return properties;
    }
}
