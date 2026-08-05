package com.voicenote.messaging;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.service.PipelineProgressService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherTest {
    @Test
    void failsThePipelineInsteadOfDeferringWhenBrokerDeliveryFails() {
        AppProperties properties = new AppProperties();
        properties.getWorkers().setEnabled(true);
        OutboxDispatchState state = mock(OutboxDispatchState.class);
        MessagePublisher publisher = mock(MessagePublisher.class);
        PipelineProgressService pipeline = mock(PipelineProgressService.class);
        OutboxEvent event = new OutboxEvent("transcription_task", "task-id", EventType.TRANSCRIPTION_REQUESTED, "{}", null);
        RuntimeException failure = new IllegalStateException("send timeout");
        when(state.readyIds()).thenReturn(List.of(event.getId()));
        when(state.load(event.getId())).thenReturn(event);
        doThrow(failure).when(publisher).publish(event);

        new OutboxDispatcher(properties, state, publisher, pipeline).dispatch();

        verify(state).markFailed(event.getId());
        verify(pipeline).failDelivery(event, failure);
    }
}
