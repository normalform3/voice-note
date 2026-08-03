package com.voicenote.messaging;

import com.voicenote.domain.OutboxEvent;
import com.voicenote.domain.OutboxStatus;
import com.voicenote.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxDispatchState {
    private final OutboxEventRepository events;
    public OutboxDispatchState(OutboxEventRepository events) { this.events = events; }
    @Transactional(readOnly = true) public List<String> readyIds() { return events.findTop50ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc(OutboxStatus.READY, Instant.now()).stream().map(OutboxEvent::getId).toList(); }
    @Transactional(readOnly = true) public OutboxEvent load(String id) { return events.findById(id).orElseThrow(); }
    @Transactional public void markPublished(String id) { events.findById(id).ifPresent(event -> { if (event.getStatus() == OutboxStatus.READY) { event.markPublished(); events.save(event); } }); }
    @Transactional public void defer(String id) { events.findById(id).ifPresent(event -> { event.defer(); events.save(event); }); }
}
