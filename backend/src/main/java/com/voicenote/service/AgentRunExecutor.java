package com.voicenote.service;

import com.voicenote.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class AgentRunExecutor {
    private final AppProperties properties;
    private final KnowledgeAgentWorker worker;
    private final Executor executor;

    public AgentRunExecutor(AppProperties properties, KnowledgeAgentWorker worker,
                            @Qualifier("agentImmediateExecutor") Executor executor) {
        this.properties = properties; this.worker = worker; this.executor = executor;
    }

    public void execute(String runId) {
        if (!properties.getWorkers().isEnabled()) return;
        try { executor.execute(() -> worker.process(runId)); }
        catch (RejectedExecutionException ignored) {
            // The scheduled worker will claim the Run when immediate capacity is exhausted.
        }
    }
}
