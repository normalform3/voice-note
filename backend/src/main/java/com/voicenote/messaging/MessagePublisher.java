package com.voicenote.messaging;

import com.voicenote.domain.OutboxEvent;
public interface MessagePublisher { void publish(OutboxEvent event); }
