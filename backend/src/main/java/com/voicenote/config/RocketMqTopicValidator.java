package com.voicenote.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Fails fast so an invalid broker destination cannot strand durable outbox work. */
@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "true")
public class RocketMqTopicValidator {
    private static final Pattern VALID_TOPIC = Pattern.compile("^[%|a-zA-Z0-9_-]+$");

    private final AppProperties properties;

    public RocketMqTopicValidator(AppProperties properties) { this.properties = properties; }

    @PostConstruct
    void validateConfiguredTopics() {
        validate("app.rocketmq.transcription-topic", properties.getRocketmq().getTranscriptionTopic());
        validate("app.rocketmq.document-topic", properties.getRocketmq().getDocumentTopic());
        validate("app.rocketmq.knowledge-topic", properties.getRocketmq().getKnowledgeTopic());
        validate("app.rocketmq.analysis-topic", properties.getRocketmq().getAnalysisTopic());
    }

    static void validate(String property, String topic) {
        if (topic == null || !VALID_TOPIC.matcher(topic).matches()) {
            throw new IllegalStateException(property + " must match " + VALID_TOPIC.pattern()
                    + "; use letters, numbers, %, _, or - (for example voicenote-transcription)");
        }
    }
}
