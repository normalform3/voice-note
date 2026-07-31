package com.echotrace.repository;

import com.echotrace.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
    Optional<IdempotencyRecord> findByOwnerIdAndOperationNameAndIdempotencyKey(String ownerId, String operationName, String idempotencyKey);
}
