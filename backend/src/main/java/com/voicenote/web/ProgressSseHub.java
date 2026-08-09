package com.voicenote.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Component
public class ProgressSseHub {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> emitterFactory;

    public ProgressSseHub() { this(() -> new SseEmitter(0L)); }
    ProgressSseHub(Supplier<SseEmitter> emitterFactory) { this.emitterFactory = emitterFactory; }

    public SseEmitter subscribe(String ownerId, Object snapshot) {
        SseEmitter emitter = emitterFactory.get();
        subscribers.computeIfAbsent(ownerId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(ownerId, emitter));
        emitter.onTimeout(() -> remove(ownerId, emitter));
        emitter.onError(ignored -> remove(ownerId, emitter));
        try { emitter.send(SseEmitter.event().name("snapshot").data(snapshot)); }
        catch (IOException | IllegalStateException exception) { remove(ownerId, emitter); }
        return emitter;
    }

    public void send(String ownerId, String eventName, Object payload) {
        List<SseEmitter> ownerSubscribers = subscribers.get(ownerId); if (ownerSubscribers == null) return;
        for (SseEmitter emitter : ownerSubscribers) {
            try { emitter.send(SseEmitter.event().name(eventName).data(payload)); }
            catch (IOException | IllegalStateException exception) { remove(ownerId, emitter); }
        }
    }

    private void remove(String ownerId, SseEmitter emitter) {
        subscribers.computeIfPresent(ownerId, (ignored, values) -> { values.remove(emitter); return values.isEmpty() ? null : values; });
    }
}
