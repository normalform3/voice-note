package com.echotrace.messaging;

import com.echotrace.config.AppProperties;
import com.echotrace.domain.OutboxEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "true")
public class RocketMqMessagePublisher implements MessagePublisher {
    private final RocketMQTemplate rocket;
    private final AppProperties properties;
    public RocketMqMessagePublisher(RocketMQTemplate rocket, AppProperties properties) { this.rocket = rocket; this.properties = properties; }
    @Override public void publish(OutboxEvent event) {
        String topic = event.getEventType().name().startsWith("ANALYSIS") ? properties.getRocketmq().getAnalysisTopic() : properties.getRocketmq().getTranscriptionTopic();
        rocket.syncSend(topic, event.getId());
    }
}
