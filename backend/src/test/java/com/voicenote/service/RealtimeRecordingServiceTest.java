package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.EventType;
import com.voicenote.domain.RealtimeRecordingPart;
import com.voicenote.domain.RealtimeRecordingSession;
import com.voicenote.repository.RealtimeRecordingPartRepository;
import com.voicenote.repository.RealtimeRecordingSessionRepository;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeRecordingServiceTest {
    private final RealtimeRecordingSessionRepository sessions = mock(RealtimeRecordingSessionRepository.class);
    private final RealtimeRecordingPartRepository parts = mock(RealtimeRecordingPartRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final RealtimeRecordingService service = new RealtimeRecordingService(sessions, parts, storage, outbox, new ObjectMapper(), enabledProperties());

    @Test
    void storesSequentialPartsAndRepairsSameHashRetryAfterFinalizationFailure() throws Exception {
        RealtimeRecordingSession session = recording();
        byte[] content = "first-ten-seconds".getBytes();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        AtomicReference<byte[]> stored = new AtomicReference<>();
        when(sessions.findOwnedForUpdate(session.getId(), "session-owner")).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> { stored.set(call.<java.io.InputStream>getArgument(1).readAllBytes()); return null; })
                .when(storage).put(anyString(), any(), eq((long) content.length), eq("application/octet-stream"));

        service.uploadPart("session-owner", session.getId(), 0, content.length, sha256, new ByteArrayInputStream(content));

        assertThat(session.getNextPartNumber()).isEqualTo(1);
        assertThat(session.getTotalBytes()).isEqualTo(content.length);
        assertThat(stored.get()).isEqualTo(content);

        RealtimeRecordingPart existing = new RealtimeRecordingPart(session.getId(), 0, "temporary/part-0", content.length, sha256);
        when(parts.findBySessionIdAndPartNumber(session.getId(), 0)).thenReturn(Optional.of(existing));
        session.fail("RECORDING_FINALIZATION_FAILED", "temporary object missing");
        service.uploadPart("session-owner", session.getId(), 0, content.length, sha256, new ByteArrayInputStream(content));
        verify(storage, times(2)).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void rejectsOutOfOrderDifferentAndCrossUserParts() throws Exception {
        RealtimeRecordingSession session = recording();
        byte[] content = "audio".getBytes();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        when(sessions.findOwnedForUpdate(session.getId(), "session-owner")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.uploadPart("session-owner", session.getId(), 1, content.length, sha256, new ByteArrayInputStream(content)))
                .isInstanceOf(ApiException.class).hasMessageContaining("下一个分片");
        assertThatThrownBy(() -> service.uploadPart("another-owner", session.getId(), 0, content.length, sha256, new ByteArrayInputStream(content)))
                .isInstanceOf(ApiException.class).hasMessageContaining("不存在");

        when(parts.findBySessionIdAndPartNumber(session.getId(), 0))
                .thenReturn(Optional.of(new RealtimeRecordingPart(session.getId(), 0, "temporary/part-0", content.length, "b".repeat(64))));
        assertThatThrownBy(() -> service.uploadPart("session-owner", session.getId(), 0, content.length, sha256, new ByteArrayInputStream(content)))
                .isInstanceOf(ApiException.class).hasMessageContaining("不同内容");
    }

    @Test
    void removesTheTemporaryObjectWhenTheUploadedBytesDoNotMatchTheDeclaredHash() {
        RealtimeRecordingSession session = recording();
        byte[] content = "actual-audio".getBytes();
        when(sessions.findOwnedForUpdate(session.getId(), "session-owner")).thenReturn(Optional.of(session));
        doAnswer(call -> { call.<java.io.InputStream>getArgument(1).readAllBytes(); return null; })
                .when(storage).put(anyString(), any(), eq((long) content.length), eq("application/octet-stream"));

        assertThatThrownBy(() -> service.uploadPart("session-owner", session.getId(), 0, content.length,
                "f".repeat(64), new ByteArrayInputStream(content)))
                .isInstanceOf(ApiException.class).hasMessageContaining("校验失败");

        verify(storage).removeQuietly(contains(session.getId()));
        verify(parts, never()).save(any());
    }

    @Test
    void queuesFinalizationOnlyAfterAllPartsArePresent() {
        RealtimeRecordingSession session = recording();
        session.partReceived(0, 12);
        when(sessions.findOwnedForUpdate(session.getId(), "session-owner")).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThatThrownBy(() -> service.complete("session-owner", session.getId(), 2))
                .isInstanceOf(ApiException.class).hasMessageContaining("尚未全部上传");

        service.complete("session-owner", session.getId(), 1);
        verify(outbox).enqueue("realtime_recording", session.getId(), EventType.RECORDING_FINALIZATION_REQUESTED);
    }

    @Test
    void requeuesAStalledFinalizationWithoutChangingItsParts() {
        RealtimeRecordingSession session = recording();
        session.partReceived(0, 12);
        session.beginFinalizing(1);
        when(sessions.findOwnedForUpdate(session.getId(), "session-owner")).thenReturn(Optional.of(session));

        service.complete("session-owner", session.getId(), 1);

        verify(outbox).enqueue("realtime_recording", session.getId(), EventType.RECORDING_FINALIZATION_REQUESTED);
        assertThat(session.getStatus()).isEqualTo(com.voicenote.domain.RealtimeRecordingStatus.FINALIZING);
    }

    @Test
    void storesTheSelectedHotwordForFinalTranscription() throws Exception {
        TranscriptionTaskService transcriptionTasks = mock(TranscriptionTaskService.class);
        ObjectMapper mapper = new ObjectMapper();
        RealtimeRecordingService hotwordService = new RealtimeRecordingService(
                sessions, parts, storage, outbox, mapper, enabledProperties(), transcriptionTasks);
        var requested = new TranscriptionTaskService.AsrConfig(List.of("zh", "en"), true, null, "library-1");
        var resolved = new TranscriptionTaskService.StoredAsrConfig(
                List.of("en", "zh"), true, null, "library-1", 3, "vocab-1");
        when(transcriptionTasks.resolveConfig("session-owner", requested)).thenReturn(resolved);
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));

        RealtimeRecordingSession session = hotwordService.create("session-owner", new RealtimeRecordingService.CreateCommand(
                Instant.now(), "audio/webm;codecs=opus", "recording.webm", 48_000, List.of("zh", "en"), requested));

        assertThat(session.getHotwordLibraryId()).isEqualTo("library-1");
        assertThat(session.getHotwordLibraryRevision()).isEqualTo(3);
        assertThat(mapper.readTree(session.getAsrConfig()).path("vocabularyId").asText()).isEqualTo("vocab-1");
    }

    private static RealtimeRecordingSession recording() {
        return new RealtimeRecordingSession("session-owner", "audio/webm;codecs=opus", "recording.webm", 48_000,
                "[\"zh\",\"en\"]", "{\"languageHints\":[\"zh\",\"en\"],\"diarizationEnabled\":true}", Instant.now());
    }

    private static AppProperties enabledProperties() {
        AppProperties properties = new AppProperties();
        properties.getDashscope().setEnabled(true);
        properties.getDashscope().setApiKey("test-key");
        properties.getRealtimeAsr().setEnabled(true);
        return properties;
    }
}
