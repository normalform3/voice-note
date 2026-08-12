package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.ProviderException;
import com.voicenote.web.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Component
public class KnowledgeAgentWorker {
    private final AppProperties properties;
    private final KnowledgeAgentService runs;
    private final KnowledgeSearchService knowledge;
    private final AnalysisModelClient legacyModel;
    private final AgentRuntime runtime;
    private final ObjectMapper mapper;
    private final AgentConversationLifecycle conversations;

    public KnowledgeAgentWorker(AppProperties properties, KnowledgeAgentService runs, KnowledgeSearchService knowledge,
                                AnalysisModelClient legacyModel, AgentRuntime runtime, ObjectMapper mapper,
                                AgentConversationLifecycle conversations) {
        this.properties = properties; this.runs = runs; this.knowledge = knowledge;
        this.legacyModel = legacyModel; this.runtime = runtime; this.mapper = mapper; this.conversations = conversations;
    }

    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() {
        if (!properties.getWorkers().isEnabled()) return;
        runs.queuedRunIds().forEach(this::run);
        conversations.recoverSettledTurns();
    }

    private void run(String runId) {
        KnowledgeAgentService.RunWork work = runs.claim(runId);
        if (work == null) return;
        if (work.legacy()) runLegacy(work); else {
            try { runtime.execute(work); }
            finally { conversations.settle(runId); }
        }
    }

    private void runLegacy(KnowledgeAgentService.RunWork run) {
        try {
            runs.consumeTool(run.runId());
            List<KnowledgeSearchService.SearchHit> hits = knowledge.searchKnowledge(run.ownerId(), run.question(), 20);
            if (hits.isEmpty()) {
                runs.complete(run.runId(), mapper.writeValueAsString(Map.of("answer", "知识库中没有足够资料回答这个问题。", "findings", List.of())), List.of());
                return;
            }
            runs.consumeTool(run.runId());
            List<KnowledgeSearchService.ReadableChunk> readable = knowledge.readExpandedContext(run.ownerId(), hits);
            String response = legacyModel.complete(legacyPrompt(run.question(), readable));
            runs.complete(run.runId(), response, readable);
        } catch (ProviderException exception) { failQuietly(run.runId(), exception.getCode() + ": " + exception.getMessage()); }
        catch (ApiException exception) { failQuietly(run.runId(), exception.getCode() + ": " + exception.getMessage()); }
        catch (Exception exception) { failQuietly(run.runId(), "KNOWLEDGE_AGENT_FAILED: " + exception.getMessage()); }
    }

    private void failQuietly(String runId, String message) {
        try { runs.fail(runId, message); }
        catch (NoSuchElementException ignored) { /* Run was deleted concurrently. */ }
    }

    private String legacyPrompt(String question, List<KnowledgeSearchService.ReadableChunk> sources) {
        StringBuilder context = new StringBuilder();
        for (KnowledgeSearchService.ReadableChunk source : sources) {
            context.append("CHUNK ").append(source.chunkId()).append(" / ").append(source.documentTitle()).append(" / ")
                    .append(source.topicTitle()).append(" / ").append(source.startMs()).append("-").append(source.endMs()).append("ms\n").append(source.content()).append("\n");
        }
        return "You are voicenote, an evidence-first audio knowledge assistant. Answer only from the supplied transcript chunks. " +
                "If the sources do not support an answer, say so plainly. Return JSON only: {\"answer\":string,\"findings\":[{\"title\":string,\"content\":string,\"evidence\":[{\"chunkId\":string,\"segmentId\":string}]}]}. " +
                "Every factual finding must cite segment IDs that appear inside its cited chunk.\n\nQuestion:\n" + question + "\n\nSources:\n" + context;
    }
}
