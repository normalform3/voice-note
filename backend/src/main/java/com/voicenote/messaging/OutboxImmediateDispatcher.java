package com.voicenote.messaging;

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
    private final Executor executor;

    public OutboxImmediateDispatcher(OutboxDispatcher dispatcher,
                                     @Qualifier("agentImmediateExecutor") Executor executor) {
        this.dispatcher = dispatcher; this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void wake(OutboxService.OutboxEnqueued event) {
        try { executor.execute(() -> dispatcher.dispatchOne(event.eventId())); }
        catch (RejectedExecutionException ignored) {
            // The scheduled dispatcher remains the bounded fallback when the immediate queue is full.
        }
    }
}
