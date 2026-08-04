package com.voicenote.repository;

import com.voicenote.domain.OutboxEvent;
import com.voicenote.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    Optional<OutboxEvent> findByDeduplicationKey(String deduplicationKey);
    @Modifying
    @Query(value = "INSERT IGNORE INTO outbox_events (id, aggregate_type, aggregate_id, event_type, deduplication_key, payload, status, attempts, available_at, created_at) VALUES (:id, :aggregateType, :aggregateId, :eventType, :deduplicationKey, :payload, :status, 0, :availableAt, :createdAt)", nativeQuery = true)
    int insertIgnore(@Param("id") String id, @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                     @Param("eventType") String eventType, @Param("deduplicationKey") String deduplicationKey, @Param("payload") String payload,
                     @Param("status") String status, @Param("availableAt") Instant availableAt, @Param("createdAt") Instant createdAt);
    List<OutboxEvent> findTop50ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc(OutboxStatus status, Instant now);
    void deleteByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);
}
