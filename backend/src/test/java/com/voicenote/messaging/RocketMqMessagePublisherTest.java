package com.voicenote.messaging;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqMessagePublisherTest {
    @Test
    void sendsTranscriptionEventsToTheConfiguredTopicWithAnEventTag() {
        RocketMQTemplate rocket = mock(RocketMQTemplate.class);
        AppProperties properties = new AppProperties();
        properties.getRocketmq().setTranscriptionTopic("voicenote-transcription");
        OutboxEvent event = new OutboxEvent("transcription_task", "task-id", EventType.TRANSCRIPTION_REQUESTED, "{}", null);

        new RocketMqMessagePublisher(rocket, properties).publish(event);

        verify(rocket).syncSend("voicenote-transcription:TRANSCRIPTION_REQUESTED", event.getId());
    }
}
