package com.echotrace.service;

import com.echotrace.domain.IdempotencyRecord;
import com.echotrace.repository.IdempotencyRecordRepository;
import com.echotrace.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final IdempotencyRecordRepository repository;
    public IdempotencyService(IdempotencyRecordRepository repository) { this.repository = repository; }

    @Transactional
    public IdempotencyRecord reserve(String ownerId, String operation, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "A valid Idempotency-Key header is required");
        }
        return repository.findByOwnerIdAndOperationNameAndIdempotencyKey(ownerId, operation, key)
                .map(existing -> {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "The key was already used with a different request");
                    }
                    return existing;
                })
                .orElseGet(() -> repository.save(new IdempotencyRecord(ownerId, operation, key, requestHash)));
    }

    @Transactional
    public void complete(IdempotencyRecord record, String resourceId, String responseJson) {
        if (record.getResourceId() == null) {
            record.complete(resourceId, HttpStatus.OK.value(), responseJson);
            repository.save(record);
        }
    }
}
