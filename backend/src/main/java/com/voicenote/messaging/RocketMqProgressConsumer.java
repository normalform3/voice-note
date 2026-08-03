package com.voicenote.messaging;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(topic = "${app.rocketmq.transcription-topic}", consumerGroup = "voicenote-progress-consumer", selectorExpression = "PROGRESS_CHANGED", messageModel = MessageModel.BROADCASTING)
public class RocketMqProgressConsumer implements RocketMQListener<String> {
    private final ProgressMessageHandler handler;
    public RocketMqProgressConsumer(ProgressMessageHandler handler) { this.handler = handler; }
    @Override public void onMessage(String eventId) { handler.consume("rocketmq-progress", eventId); }
}
