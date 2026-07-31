package com.echotrace.messaging;

import com.echotrace.domain.OutboxEvent;
public interface MessagePublisher { void publish(OutboxEvent event); }
