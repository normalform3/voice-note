package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.RealtimeRecordingPartRepository;
import com.voicenote.repository.RealtimeRecordingSessionRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class RealtimeRecordingService {
    private static final long MAX_PART_BYTES = 5L * 1024 * 1024;
    private static final long MAX_RECORDING_BYTES = 1024L * 1024 * 1024;
    private static final Set<String> LANGUAGES = Set.of("zh", "en");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final RealtimeRecordingSessionRepository sessions;
    private final RealtimeRecordingPartRepository parts;
    private final ObjectStorage storage;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final TranscriptionTaskService transcriptionTasks;

    @Autowired
    public RealtimeRecordingService(RealtimeRecordingSessionRepository sessions, RealtimeRecordingPartRepository parts,
                                    ObjectStorage storage, OutboxService outbox, ObjectMapper mapper, AppProperties properties,
                                    TranscriptionTaskService transcriptionTasks) {
        this.sessions = sessions; this.parts = parts; this.storage = storage; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
        this.transcriptionTasks = transcriptionTasks;
    }

    RealtimeRecordingService(RealtimeRecordingSessionRepository sessions, RealtimeRecordingPartRepository parts,
                             ObjectStorage storage, OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this(sessions, parts, storage, outbox, mapper, properties, null);
    }

    public boolean isEnabled() {
        var config = properties.getRealtimeAsr();
        if (!config.isEnabled() || !properties.getDashscope().isEnabled() || blank(properties.getDashscope().getApiKey())
                || blank(config.getModel()) || blank(config.getWsUrl())) return false;
        try { return "wss".equalsIgnoreCase(URI.create(config.getWsUrl()).getScheme()); }
        catch (IllegalArgumentException exception) { return false; }
    }

    @Transactional
    public RealtimeRecordingSession create(String ownerId, CreateCommand command) {
        if (!isEnabled()) throw new ApiException(HttpStatus.NOT_FOUND, "REALTIME_ASR_DISABLED", "实时录音转写尚未启用");
        validate(command);
        try {
            List<String> languages = normalizeLanguages(command.languageHints());
            TranscriptionTaskService.AsrConfig requested = command.asrConfig() == null ? TranscriptionTaskService.AsrConfig.defaultConfig() : command.asrConfig();
            TranscriptionTaskService.StoredAsrConfig asr = transcriptionTasks == null
                    ? new TranscriptionTaskService.StoredAsrConfig(requested.languageHints(), requested.diarizationEnabled(), requested.speakerCount(), null, null, null).normalized()
                    : transcriptionTasks.resolveConfig(ownerId, requested);
            RealtimeRecordingSession session = new RealtimeRecordingSession(ownerId, command.contentType().trim(), command.originalFilename().trim(), command.sampleRate(),
                    mapper.writeValueAsString(languages), mapper.writeValueAsString(asr), command.startedAt());
            session.attachHotword(asr.hotwordLibraryId(), asr.hotwordLibraryRevision());
            return sessions.save(session);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("Cannot create realtime recording session", exception); }
    }

    @Transactional(readOnly = true)
    public RealtimeRecordingSession owned(String ownerId, String sessionId) {
        return sessions.findByIdAndOwnerId(sessionId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "实时录音会话不存在"));
    }

    @Transactional
    public void uploadPart(String ownerId, String sessionId, int partNumber, long contentLength, String expectedSha256, InputStream input) {
        if (partNumber < 0) throw bad("INVALID_PART_NUMBER", "录音分片序号无效");
        if (contentLength <= 0 || contentLength > MAX_PART_BYTES) throw bad("INVALID_PART_SIZE", "单个录音分片必须小于 5MB");
        if (expectedSha256 == null || !expectedSha256.matches("[a-fA-F0-9]{64}")) throw bad("INVALID_PART_HASH", "录音分片缺少有效 SHA-256");
        RealtimeRecordingSession session = locked(ownerId, sessionId);
        var existing = parts.findBySessionIdAndPartNumber(sessionId, partNumber).orElse(null);
        if (existing != null) {
            if (session.getStatus() != RealtimeRecordingStatus.RECORDING && session.getStatus() != RealtimeRecordingStatus.FAILED) {
                throw new ApiException(HttpStatus.CONFLICT, "RECORDING_NOT_WRITABLE", "录音会话已停止接收分片");
            }
            if (existing.getContentLength() == contentLength && existing.getSha256().equalsIgnoreCase(expectedSha256)) {
                // Re-write an idempotent retry instead of trusting database metadata alone.
                // This repairs a temporary object that disappeared after the row was committed.
                storePartObject(existing.getObjectKey(), contentLength, expectedSha256, input);
                return;
            }
            throw new ApiException(HttpStatus.CONFLICT, "PART_CONTENT_CONFLICT", "同一录音分片序号已保存了不同内容");
        }
        if (session.getStatus() != RealtimeRecordingStatus.RECORDING) throw new ApiException(HttpStatus.CONFLICT, "RECORDING_NOT_WRITABLE", "录音会话已停止接收分片");
        if (partNumber != session.getNextPartNumber()) throw new ApiException(HttpStatus.CONFLICT, "PART_OUT_OF_SEQUENCE", "请从服务端要求的下一个分片继续上传");
        if (session.getTotalBytes() + contentLength > MAX_RECORDING_BYTES) throw bad("RECORDING_TOO_LARGE", "录音文件不能超过 1GB");
        String objectKey = partObjectKey(session, partNumber);
        storePartObject(objectKey, contentLength, expectedSha256, input);
        parts.save(new RealtimeRecordingPart(sessionId, partNumber, objectKey, contentLength, expectedSha256.toLowerCase()));
        session.partReceived(partNumber, contentLength); sessions.save(session);
    }

    private void storePartObject(String objectKey, long contentLength, String expectedSha256, InputStream input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            storage.put(objectKey, new DigestInputStream(input, digest), contentLength, "application/octet-stream");
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                storage.removeQuietly(objectKey);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PART_HASH_MISMATCH", "录音分片内容校验失败");
            }
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            storage.removeQuietly(objectKey);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RECORDING_PART_UPLOAD_FAILED", "录音分片保存失败");
        }
    }

    @Transactional
    public RealtimeRecordingSession complete(String ownerId, String sessionId, int partCount) {
        RealtimeRecordingSession session = locked(ownerId, sessionId);
        if (session.getStatus() == RealtimeRecordingStatus.READY || session.getStatus() == RealtimeRecordingStatus.PROCESSING) return session;
        if (session.getStatus() == RealtimeRecordingStatus.FINALIZING) {
            outbox.enqueue("realtime_recording", sessionId, EventType.RECORDING_FINALIZATION_REQUESTED);
            return session;
        }
        if (partCount <= 0 || partCount != session.getNextPartNumber()) {
            throw new ApiException(HttpStatus.CONFLICT, "RECORDING_PARTS_INCOMPLETE", "录音分片尚未全部上传");
        }
        session.beginFinalizing(partCount); sessions.save(session);
        outbox.enqueue("realtime_recording", sessionId, EventType.RECORDING_FINALIZATION_REQUESTED);
        return session;
    }

    @Transactional
    public void abort(String ownerId, String sessionId) {
        RealtimeRecordingSession session = locked(ownerId, sessionId);
        if (session.getStatus() == RealtimeRecordingStatus.READY) throw new ApiException(HttpStatus.CONFLICT, "RECORDING_ALREADY_ARCHIVED", "录音已经归档");
        session.abort(); sessions.save(session);
    }

    public List<String> languages(RealtimeRecordingSession session) {
        try { return mapper.readValue(session.getLanguageHints(), STRING_LIST); }
        catch (Exception exception) { return List.of("zh", "en"); }
    }

    @Scheduled(fixedDelayString = "${app.realtime-asr.cleanup-interval-ms:3600000}")
    public void cleanupExpired() {
        List<RealtimeRecordingStatus> removable = List.of(RealtimeRecordingStatus.RECORDING, RealtimeRecordingStatus.FAILED,
                RealtimeRecordingStatus.ABORTED, RealtimeRecordingStatus.READY, RealtimeRecordingStatus.EXPIRED);
        for (RealtimeRecordingSession session : sessions.findTop100ByStatusInAndExpiresAtBefore(removable, Instant.now())) {
            for (RealtimeRecordingPart part : parts.findBySessionIdOrderByPartNumber(session.getId())) storage.removeQuietly(part.getObjectKey());
            parts.deleteBySessionId(session.getId()); sessions.delete(session);
        }
    }

    private static void validate(CreateCommand command) {
        if (command == null || command.startedAt() == null) throw bad("RECORDING_START_REQUIRED", "缺少录音开始时间");
        Instant now = Instant.now();
        if (command.startedAt().isAfter(now.plusSeconds(60)) || command.startedAt().isBefore(now.minusSeconds(24 * 3600))) throw bad("INVALID_RECORDING_START", "录音开始时间无效");
        if (command.contentType() == null || !command.contentType().toLowerCase().startsWith("audio/webm")) throw bad("UNSUPPORTED_RECORDING_FORMAT", "实时录音仅支持 WebM/Opus");
        if (command.originalFilename() == null || command.originalFilename().isBlank() || command.originalFilename().length() > 512) throw bad("INVALID_RECORDING_NAME", "录音文件名无效");
        if (command.sampleRate() < 8000 || command.sampleRate() > 96000) throw bad("INVALID_SAMPLE_RATE", "采样率必须在 8kHz 到 96kHz 之间");
    }
    private static List<String> normalizeLanguages(List<String> values) {
        List<String> normalized = values == null || values.isEmpty() ? List.of("zh", "en") : values.stream().distinct().sorted().toList();
        if (normalized.isEmpty() || !LANGUAGES.containsAll(normalized)) throw bad("UNSUPPORTED_LANGUAGE", "首版实时录音只支持中文和英文");
        return normalized;
    }
    private static String partObjectKey(RealtimeRecordingSession session, int partNumber) {
        return "owners/" + session.getOwnerId() + "/realtime-recordings/" + session.getId() + "/parts/" + String.format("%06d", partNumber);
    }
    private RealtimeRecordingSession locked(String ownerId, String sessionId) {
        return sessions.findOwnedForUpdate(sessionId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "实时录音会话不存在"));
    }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record CreateCommand(Instant startedAt, String contentType, String originalFilename, int sampleRate,
                                List<String> languageHints, TranscriptionTaskService.AsrConfig asrConfig) { }
}
