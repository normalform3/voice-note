package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.AnalysisModelClient;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAgentWorkerImmediateClaimTest {
    @Test
    void immediateAndPollingWakeupsExecuteAClaimedRunOnlyOnce() {
        AppProperties properties = new AppProperties(); properties.getWorkers().setEnabled(true);
        KnowledgeAgentService runs = mock(KnowledgeAgentService.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        AgentConversationLifecycle conversations = mock(AgentConversationLifecycle.class);
        KnowledgeAgentService.RunWork work = new KnowledgeAgentService.RunWork("run", "owner", "question", false, 1, false);
        when(runs.claim("run")).thenReturn(work, null);
        KnowledgeAgentWorker worker = new KnowledgeAgentWorker(properties, runs, mock(KnowledgeSearchService.class),
                mock(AnalysisModelClient.class), runtime, new ObjectMapper(), conversations);

        worker.process("run");
        worker.process("run");

        verify(runtime, times(1)).execute(work);
        verify(conversations, times(1)).settle("run");
    }
}
