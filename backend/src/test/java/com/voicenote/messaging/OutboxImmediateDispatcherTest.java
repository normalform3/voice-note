package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.service.OutboxService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxImmediateDispatcherTest {
    @Test
    void routesRecordingFinalizationAwayFromTheSharedAgentExecutor() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        CapturingExecutor shared = new CapturingExecutor();
        CapturingExecutor recordings = new CapturingExecutor();
        OutboxImmediateDispatcher listener = new OutboxImmediateDispatcher(dispatcher, shared, recordings);

        listener.wake(new OutboxService.OutboxEnqueued("recording-event", EventType.RECORDING_FINALIZATION_REQUESTED));

        assertThat(shared.tasks).isEmpty();
        assertThat(recordings.tasks).hasSize(1);
        recordings.runAll();
        verify(dispatcher).dispatchOne("recording-event");
    }

    @Test
    void keepsOtherEventsOnTheSharedExecutor() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        CapturingExecutor shared = new CapturingExecutor();
        CapturingExecutor recordings = new CapturingExecutor();
        OutboxImmediateDispatcher listener = new OutboxImmediateDispatcher(dispatcher, shared, recordings);

        listener.wake(new OutboxService.OutboxEnqueued("analysis-event", EventType.ANALYSIS_REQUESTED));

        assertThat(shared.tasks).hasSize(1);
        assertThat(recordings.tasks).isEmpty();
        shared.runAll();
        verify(dispatcher).dispatchOne("analysis-event");
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private void runAll() { tasks.forEach(Runnable::run); }
    }
}
