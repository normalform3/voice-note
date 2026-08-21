package com.voicenote.service;

import com.voicenote.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class MemoryRunExecutor {
    private final AppProperties properties;
    private final MemoryWorker worker;
    private final Executor executor;

    public MemoryRunExecutor(AppProperties properties, MemoryWorker worker,
                             @Qualifier("agentImmediateExecutor") Executor executor) {
        this.properties = properties; this.worker = worker; this.executor = executor;
    }

    public void processExtraction(String turnId) {
        execute(() -> worker.processExtraction(turnId));
    }

    public void processSummary(String conversationId) {
        execute(() -> worker.processSummary(conversationId));
    }

    public void processIndex(String versionId) {
        execute(() -> worker.processIndex(versionId));
    }

    public void processDeletion(String deletionId) {
        execute(() -> worker.processDeletion(deletionId));
    }

    private void execute(Runnable action) {
        if (!properties.getWorkers().isEnabled() || !properties.getMemory().isEnabled()) return;
        try { executor.execute(action); }
        catch (RejectedExecutionException ignored) {
            // The scheduled memory worker will claim queued work when immediate capacity is exhausted.
        }
    }
}
