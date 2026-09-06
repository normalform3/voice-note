package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.repository.RealtimeRecordingPartRepository;
import com.voicenote.repository.RealtimeRecordingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeRecordingWorker {
    private static final Logger log = LoggerFactory.getLogger(RealtimeRecordingWorker.class);
    private final RealtimeRecordingSessionRepository sessions;
    private final RealtimeRecordingPartRepository parts;
    private final AudioBlobRepository blobs;
    private final ObjectStorage storage;
    private final TranscriptionTaskService transcriptionTasks;
    private final ProgressEventPublisher progress;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public RealtimeRecordingWorker(RealtimeRecordingSessionRepository sessions, RealtimeRecordingPartRepository parts,
                                   AudioBlobRepository blobs, ObjectStorage storage, TranscriptionTaskService transcriptionTasks,
                                   ProgressEventPublisher progress, ObjectMapper mapper, AppProperties properties) {
        this.sessions = sessions; this.parts = parts; this.blobs = blobs; this.storage = storage;
        this.transcriptionTasks = transcriptionTasks; this.progress = progress; this.mapper = mapper; this.properties = properties;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(String sessionId) {
        if (!activeSessions.add(sessionId)) return;
        long startedAt = System.nanoTime();
        boolean claimed = false;
        try {
            if (sessions.claimFinalization(sessionId, Instant.now().minusSeconds(5 * 60)) == 0) return;
            claimed = true;
            processOnce(sessionId);
        }
        finally {
            activeSessions.remove(sessionId);
            if (claimed) {
                log.info("Realtime recording finalization finished: sessionId={}, elapsedMs={}", sessionId,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            }
        }
    }

    private void processOnce(String sessionId) {
        RealtimeRecordingSession session = sessions.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() == RealtimeRecordingStatus.READY) return;
        if (session.getStatus() != RealtimeRecordingStatus.PROCESSING) return;
        List<RealtimeRecordingPart> ordered = parts.findBySessionIdOrderByPartNumber(sessionId);
        TranscriptionTask task;
        try {
            String finalKey = "owners/" + session.getOwnerId() + "/audio/" + session.getId() + "/source";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var existingFinalSize = storage.sizeIfExists(finalKey);
            if (existingFinalSize.isPresent()) {
                if (existingFinalSize.getAsLong() != session.getTotalBytes()) throw new IllegalStateException("Existing final recording has an invalid byte count");
                digestObject(finalKey, digest);
            } else {
                validateParts(session, ordered);
                Path mergedAudio = Files.createTempFile("voicenote-recording-", ".webm");
                try {
                    mergeToLocalFile(ordered, mergedAudio, digest);
                    long mergedBytes = Files.size(mergedAudio);
                    if (mergedBytes != session.getTotalBytes()) throw new IllegalStateException("Recording byte count does not match its parts");
                    try (InputStream completeAudio = Files.newInputStream(mergedAudio)) {
                        storage.put(finalKey, completeAudio, mergedBytes, session.getContentType());
                    }
                } finally {
                    Files.deleteIfExists(mergedAudio);
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            AudioBlob blob = blobs.findByOwnerIdAndSha256(session.getOwnerId(), sha256).orElse(null);
            if (blob == null) blob = blobs.save(AudioBlob.readyRecording(session.getId(), session.getOwnerId(), sha256,
                    session.getTotalBytes(), session.getContentType(), session.getOriginalFilename()));
            else if (!blob.getId().equals(session.getId())) storage.removeQuietly(finalKey);

            TranscriptionTaskService.AsrConfig asr = mapper.readValue(session.getAsrConfig(), TranscriptionTaskService.AsrConfig.class);
            task = transcriptionTasks.create(session.getOwnerId(), "realtime-final-" + sessionId,
                    new TranscriptionTaskService.CreateTaskCommand(blob.getId(), asr), Instant.now());
            transcriptionTasks.updateMetadata(session.getOwnerId(), task.getId(), session.getStartedAt(), SceneType.OTHER, null, List.of());
            session.ready(blob.getId(), task.getId()); sessions.save(session);
        } catch (Exception exception) {
            String message = safe(exception.getMessage());
            try {
                session.fail("RECORDING_FINALIZATION_FAILED", message);
                sessions.saveAndFlush(session);
            } catch (RuntimeException staleWorker) {
                log.info("Ignored stale realtime recording failure: sessionId={}, exceptionType={}", sessionId,
                        staleWorker.getClass().getSimpleName());
                return;
            }
            publishQuietly(new ProgressEventPublisher.ProgressNotification(session.getOwnerId(), "realtime-recording-failed", sessionId,
                    java.util.Map.of("sessionId", sessionId, "message", message)));
            log.warn("Realtime recording finalization failed: sessionId={}, exceptionType={}", sessionId, exception.getClass().getSimpleName());
            return;
        }
        try { cleanupParts(ordered, sessionId); }
        catch (RuntimeException exception) {
            log.warn("Realtime recording temporary-part cleanup failed: sessionId={}, exceptionType={}", sessionId, exception.getClass().getSimpleName());
        }
        publishQuietly(new ProgressEventPublisher.ProgressNotification(session.getOwnerId(), "realtime-recording-settled", sessionId,
                java.util.Map.of("sessionId", sessionId, "taskId", task.getId())));
    }

    @Scheduled(fixedDelayString = "${app.realtime-asr.finalization-recovery-interval-ms:5000}")
    public void recoverStrandedFinalizations() {
        if (!properties.getWorkers().isEnabled()) return;
        Instant cutoff = Instant.now().minusSeconds(5);
        sessions.findTop20ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        List.of(RealtimeRecordingStatus.FINALIZING, RealtimeRecordingStatus.PROCESSING), cutoff)
                .forEach(session -> process(session.getId()));
        sessions.findTop20ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(RealtimeRecordingStatus.FAILED, cutoff).stream()
                .filter(this::hasCompleteFinalObject)
                .forEach(session -> process(session.getId()));
    }

    private boolean hasCompleteFinalObject(RealtimeRecordingSession session) {
        try {
            String key = "owners/" + session.getOwnerId() + "/audio/" + session.getId() + "/source";
            var size = storage.sizeIfExists(key);
            return size.isPresent() && size.getAsLong() == session.getTotalBytes();
        } catch (RuntimeException exception) {
            log.warn("Realtime recording recovery probe failed: sessionId={}, exceptionType={}", session.getId(),
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void validateParts(RealtimeRecordingSession session, List<RealtimeRecordingPart> ordered) {
        if (session.getExpectedPartCount() == null || ordered.size() != session.getExpectedPartCount()) throw new IllegalStateException("Recording parts are incomplete");
        long bytes = 0;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).getPartNumber() != index) throw new IllegalStateException("Recording parts are not contiguous");
            bytes += ordered.get(index).getContentLength();
        }
        if (bytes != session.getTotalBytes()) throw new IllegalStateException("Recording byte count does not match its parts");
    }
    private void cleanupParts(List<RealtimeRecordingPart> values, String sessionId) {
        for (RealtimeRecordingPart part : values) storage.removeQuietly(part.getObjectKey());
        parts.deleteBySessionId(sessionId);
    }
    private void publishQuietly(ProgressEventPublisher.ProgressNotification notification) {
        try { progress.publish(notification); }
        catch (RuntimeException exception) { log.warn("Realtime recording progress event failed: sessionId={}", notification.resourceId()); }
    }
    private static String safe(String value) {
        String message = value == null || value.isBlank() ? "录音归档失败" : value.replaceAll("[\\r\\n]+", " ").trim();
        return message.substring(0, Math.min(900, message.length()));
    }

    private void mergeToLocalFile(List<RealtimeRecordingPart> ordered, Path target, MessageDigest digest) throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
             DigestOutputStream measured = new DigestOutputStream(file, digest)) {
            for (RealtimeRecordingPart part : ordered) {
                try (InputStream input = storage.get(part.getObjectKey())) {
                    input.transferTo(measured);
                } catch (RuntimeException exception) {
                    throw new IOException("Recording part " + part.getPartNumber() + " is unavailable", exception);
                }
            }
        }
    }

    private void digestObject(String objectKey, MessageDigest digest) throws IOException {
        try (InputStream input = storage.get(objectKey)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Existing final recording is unavailable", exception);
        }
    }
}
