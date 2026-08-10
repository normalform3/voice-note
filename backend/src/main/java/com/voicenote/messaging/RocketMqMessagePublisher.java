package com.voicenote.messaging;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.OutboxEvent;
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
        String topic = switch (event.getEventType()) {
            case TRANSCRIPTION_REQUESTED, PROGRESS_CHANGED -> properties.getRocketmq().getTranscriptionTopic();
            case DOCUMENT_ORGANIZATION_REQUESTED -> properties.getRocketmq().getDocumentTopic();
            case KNOWLEDGE_INDEX_REQUESTED -> properties.getRocketmq().getKnowledgeTopic();
            case ANALYSIS_REQUESTED, SPEAKER_CORRECTION_REQUESTED, KNOWLEDGE_RUN_REQUESTED -> properties.getRocketmq().getAnalysisTopic();
        };
        rocket.syncSend(topic + ":" + event.getEventType().name(), event.getId());
    }
}
