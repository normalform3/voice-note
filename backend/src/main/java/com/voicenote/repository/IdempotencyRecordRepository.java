package com.voicenote.repository;

import com.voicenote.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    Optional<IdempotencyRecord> findByOwnerIdAndOperationNameAndIdempotencyKey(String ownerId, String operationName, String idempotencyKey);
    void deleteByOwnerIdAndResourceId(String ownerId, String resourceId);
}
