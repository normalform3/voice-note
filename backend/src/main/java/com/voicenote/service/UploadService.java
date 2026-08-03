package com.voicenote.service;

import com.voicenote.domain.AudioBlob;
import com.voicenote.domain.BlobStatus;
import com.voicenote.domain.IdempotencyRecord;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class UploadService {
    private static final String OPERATION = "CREATE_UPLOAD_INTENT";
    private final AudioBlobRepository blobs;
    private final IdempotencyService idempotency;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    public UploadService(AudioBlobRepository blobs, IdempotencyService idempotency, ObjectStorage storage, ObjectMapper objectMapper) {
        this.blobs = blobs; this.idempotency = idempotency; this.storage = storage; this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadIntent createIntent(String ownerId, String idempotencyKey, CreateUploadIntent command) {
        validate(command);
        String requestHash = Hashing.canonicalJsonHash(command);
        IdempotencyRecord record = idempotency.reserve(ownerId, OPERATION, idempotencyKey, requestHash);
        if (record.getResourceId() != null) return byId(ownerId, record.getResourceId());
        AudioBlob blob = blobs.findByOwnerIdAndSha256(ownerId, command.sha256()).map(existing -> {
            if (existing.getStatus() == BlobStatus.FAILED) { existing.reopenForUpload(); return blobs.save(existing); }
            return existing;
        }).orElseGet(() -> blobs.save(new AudioBlob(ownerId, command.sha256(), command.contentLength(), command.contentType(), command.originalFilename())));
        UploadIntent response = toIntent(blob);
        try { idempotency.complete(record, blob.getId(), objectMapper.writeValueAsString(response)); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent response", exception); }
        return response;
    }

    public void uploadContent(String ownerId, String blobId, InputStream content) {
        AudioBlob blob = claimWrite(ownerId, blobId);
        if (blob.getStatus() == BlobStatus.READY) return;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream measured = new DigestInputStream(content, digest)) {
                storage.put(blob.getObjectKey(), measured, blob.getContentLength(), blob.getContentType());
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(blob.getSha256())) {
                storage.removeQuietly(blob.getObjectKey());
                markFailed(blob.getId(), "Content SHA-256 did not match the upload intent");
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONTENT_HASH_MISMATCH", "Uploaded bytes do not match X-Content-SHA256");
            }
            markReady(blob.getId());
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            markFailed(blob.getId(), "Upload did not complete");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "UPLOAD_FAILED", "Audio upload failed");
        }
    }

    @Transactional(readOnly = true)
    public UploadIntent byId(String ownerId, String blobId) {
        AudioBlob blob = blobs.findById(blobId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "Audio upload was not found"));
        return toIntent(blob);
    }

    @Transactional
    protected AudioBlob claimWrite(String ownerId, String blobId) {
        AudioBlob blob = blobs.findById(blobId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AUDIO_NOT_FOUND", "Audio upload was not found"));
        if (blob.getStatus() == BlobStatus.READY) return blob;
        if (!blob.claimWrite()) throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_IN_PROGRESS", "This audio is already being uploaded");
        return blobs.save(blob);
    }

    @Transactional protected void markReady(String blobId) { AudioBlob blob = blobs.findById(blobId).orElseThrow(); blob.markReady(); blobs.save(blob); }
    @Transactional protected void markFailed(String blobId, String reason) { blobs.findById(blobId).ifPresent(blob -> { blob.markFailed(reason); blobs.save(blob); }); }

    private static UploadIntent toIntent(AudioBlob blob) { return new UploadIntent(blob.getId(), blob.getStatus(), blob.getStatus() == BlobStatus.READY, blob.getFailureReason()); }
    private static void validate(CreateUploadIntent command) {
        if (command.contentLength() <= 0 || command.contentLength() > 1024L * 1024 * 1024) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE", "Audio must be between 1 byte and 1GB");
        if (command.sha256() == null || !command.sha256().matches("[a-fA-F0-9]{64}")) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SHA256", "sha256 must be a lowercase or uppercase 64-character hex digest");
    }
    public record CreateUploadIntent(String sha256, long contentLength, String contentType, String originalFilename) { }
    public record UploadIntent(String audioBlobId, BlobStatus status, boolean contentReady, String failureReason) { }
}
