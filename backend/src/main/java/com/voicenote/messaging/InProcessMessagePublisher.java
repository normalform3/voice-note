package com.voicenote.messaging;

import com.voicenote.domain.OutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "false", matchIfMissing = true)
public class InProcessMessagePublisher implements MessagePublisher {
    private final TaskMessageHandler handler;
    public InProcessMessagePublisher(TaskMessageHandler handler) { this.handler = handler; }
    @Override public void publish(OutboxEvent event) { handler.consume("in-process", event.getId()); }
}
