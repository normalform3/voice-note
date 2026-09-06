package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.service.OutboxService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class OutboxImmediateDispatcher {
    private final OutboxDispatcher dispatcher;
    private final Executor defaultExecutor;
    private final Executor recordingExecutor;

    public OutboxImmediateDispatcher(OutboxDispatcher dispatcher,
                                     @Qualifier("agentImmediateExecutor") Executor defaultExecutor,
                                     @Qualifier("recordingFinalizationExecutor") Executor recordingExecutor) {
        this.dispatcher = dispatcher;
        this.defaultExecutor = defaultExecutor;
        this.recordingExecutor = recordingExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void wake(OutboxService.OutboxEnqueued event) {
        Executor executor = event.eventType() == EventType.RECORDING_FINALIZATION_REQUESTED
                ? recordingExecutor : defaultExecutor;
        try { executor.execute(() -> dispatcher.dispatchOne(event.eventId())); }
        catch (RejectedExecutionException ignored) {
            // The scheduled dispatcher remains the bounded fallback when the immediate queue is full.
        }
    }
}
