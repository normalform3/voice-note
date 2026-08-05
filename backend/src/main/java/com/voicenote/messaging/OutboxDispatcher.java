package com.voicenote.messaging;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.OutboxEvent;
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
        for (String eventId : state.readyIds()) {
            OutboxEvent event = state.load(eventId);
            try { publisher.publish(event); state.markPublished(eventId); }
            catch (RuntimeException failure) {
                state.markFailed(eventId);
                pipeline.failDelivery(event, failure);
            }
        }
    }
}
