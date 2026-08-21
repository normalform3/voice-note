package com.voicenote.messaging;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.domain.OutboxStatus;
import com.voicenote.service.PipelineProgressService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxDispatcher {
    private final AppProperties properties; private final OutboxDispatchState state; private final MessagePublisher publisher;
    private final PipelineProgressService pipeline;
    public OutboxDispatcher(AppProperties properties, OutboxDispatchState state, MessagePublisher publisher, PipelineProgressService pipeline) {
        this.properties = properties; this.state = state; this.publisher = publisher; this.pipeline = pipeline;
    }
    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void dispatch() {
        if (!properties.getWorkers().isEnabled()) return;
        state.readyIds().forEach(this::dispatchOne);
    }

    public void dispatchOne(String eventId) {
        if (!properties.getWorkers().isEnabled()) return;
        OutboxEvent event = state.load(eventId);
        if (event.getStatus() != OutboxStatus.READY) return;
        try { publisher.publish(event); state.markPublished(eventId); }
        catch (RuntimeException failure) {
            state.markFailed(eventId);
            pipeline.failDelivery(event, failure);
        }
    }
}
