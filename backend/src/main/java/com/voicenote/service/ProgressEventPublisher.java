package com.voicenote.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ProgressEventPublisher {
    private final ApplicationEventPublisher events;
    public ProgressEventPublisher(ApplicationEventPublisher events) { this.events = events; }
    public void publish(ProgressNotification notification) { events.publishEvent(notification); }
    public record ProgressNotification(String ownerId, String type, String resourceId) { }
}
