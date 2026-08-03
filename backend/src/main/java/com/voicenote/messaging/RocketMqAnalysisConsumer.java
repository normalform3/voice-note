package com.voicenote.messaging;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rocketmq.enabled", havingValue = "true")
@RocketMQMessageListener(topic = "${app.rocketmq.analysis-topic}", consumerGroup = "voicenote-analysis-consumer")
public class RocketMqAnalysisConsumer implements RocketMQListener<String> {
    private final TaskMessageHandler handler;
    public RocketMqAnalysisConsumer(TaskMessageHandler handler) { this.handler = handler; }
    @Override public void onMessage(String eventId) { handler.consume("rocketmq-analysis", eventId); }
}
