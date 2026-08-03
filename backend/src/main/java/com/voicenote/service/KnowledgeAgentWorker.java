package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.ProviderException;
import com.voicenote.web.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeAgentWorker {
    private final AppProperties properties;
    private final KnowledgeAgentService runs;
    private final KnowledgeSearchService knowledge;
    private final AnalysisModelClient model;
    private final ObjectMapper mapper;

    public KnowledgeAgentWorker(AppProperties properties, KnowledgeAgentService runs, KnowledgeSearchService knowledge, AnalysisModelClient model, ObjectMapper mapper) {
        this.properties = properties; this.runs = runs; this.knowledge = knowledge; this.model = model; this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() { if (properties.getWorkers().isEnabled()) runs.queuedRunIds().forEach(this::run); }

    private void run(String runId) {
        KnowledgeAgentService.RunWork run = runs.claim(runId); if (run == null) return;
        try {
            runs.consumeTool(run.runId());
            List<KnowledgeSearchService.SearchHit> hits = knowledge.searchKnowledge(run.ownerId(), run.question(), 3);
            if (hits.isEmpty()) {
                runs.complete(run.runId(), mapper.writeValueAsString(java.util.Map.of("answer", "知识库中没有足够资料回答这个问题。", "findings", List.of())), List.of());
                return;
            }
            List<KnowledgeSearchService.ReadableChunk> readable = new ArrayList<>();
            for (KnowledgeSearchService.SearchHit hit : hits) {
                runs.consumeTool(run.runId());
                readable.add(knowledge.readDocumentChunk(run.ownerId(), hit.chunkId()));
            }
            String response = model.complete(prompt(run.question(), readable));
            runs.complete(run.runId(), response, readable);
        } catch (ProviderException exception) { runs.fail(runId, exception.getCode() + ": " + exception.getMessage()); }
        catch (ApiException exception) { runs.fail(runId, exception.getCode() + ": " + exception.getMessage()); }
        catch (Exception exception) { runs.fail(runId, "KNOWLEDGE_AGENT_FAILED: " + exception.getMessage()); }
    }

    private String prompt(String question, List<KnowledgeSearchService.ReadableChunk> sources) {
        StringBuilder context = new StringBuilder();
        for (KnowledgeSearchService.ReadableChunk source : sources) {
            context.append("CHUNK ").append(source.chunkId()).append(" / ").append(source.documentTitle()).append(" / ")
                    .append(source.startMs()).append("-").append(source.endMs()).append("ms\n").append(source.content()).append("\n");
        }
        return "You are voicenote, an evidence-first audio knowledge assistant. Answer only from the supplied transcript chunks. " +
                "If the sources do not support an answer, say so plainly. Return JSON only: {\"answer\":string,\"findings\":[{\"title\":string,\"content\":string,\"evidence\":[{\"chunkId\":string,\"segmentId\":string}]}]}. " +
                "Every factual finding must cite segment IDs that appear inside its cited chunk.\n\nQuestion:\n" + question + "\n\nSources:\n" + context;
    }
}
