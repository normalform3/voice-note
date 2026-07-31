package com.echotrace.repository;

import com.echotrace.domain.OutboxEvent;
import com.echotrace.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    List<OutboxEvent> findTop50ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc(OutboxStatus status, Instant now);
}
