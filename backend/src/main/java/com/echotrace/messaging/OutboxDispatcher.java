package com.echotrace.messaging;

import com.echotrace.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxDispatcher {
    private final AppProperties properties; private final OutboxDispatchState state; private final MessagePublisher publisher;
    public OutboxDispatcher(AppProperties properties, OutboxDispatchState state, MessagePublisher publisher) { this.properties = properties; this.state = state; this.publisher = publisher; }
    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void dispatch() {
        if (!properties.getWorkers().isEnabled()) return;
        for (String eventId : state.readyIds()) {
            try { publisher.publish(state.load(eventId)); state.markPublished(eventId); }
            catch (RuntimeException failure) { state.defer(eventId); }
        }
    }
}
