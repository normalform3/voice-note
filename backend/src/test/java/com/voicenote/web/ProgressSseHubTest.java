package com.voicenote.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProgressSseHubTest {
    @Test
    void removesABrokenSubscriberWithoutCompletingTheFailedResponseAgain() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doNothing().doThrow(new IOException("Broken pipe"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        ProgressSseHub hub = new ProgressSseHub(() -> emitter);

        hub.subscribe("owner", "snapshot");
        hub.send("owner", "progress", "first");
        hub.send("owner", "progress", "ignored");

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }
}
