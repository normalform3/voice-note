package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.ConversationSummaryStatus;
import com.voicenote.domain.MemoryExtractionStatus;
import com.voicenote.domain.MemoryIndexStatus;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.AgentConversationRepository;
import com.voicenote.repository.AgentConversationTurnRepository;
import com.voicenote.repository.UserMemoryDeletionRepository;
import com.voicenote.repository.UserMemoryVersionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryWorker {
    private final AppProperties properties;
    private final AgentConversationTurnRepository turns;
    private final AgentConversationRepository conversations;
    private final UserMemoryVersionRepository versions;
    private final UserMemoryDeletionRepository deletions;
    private final UserMemoryService memories;
    private final ConversationSummaryService summaries;
    private final AnalysisModelClient model;
    private final TextEmbeddingClient embeddings;
    private final UserMemoryVectorStore vectors;
    public MemoryWorker(AppProperties properties, AgentConversationTurnRepository turns, AgentConversationRepository conversations,
                        UserMemoryVersionRepository versions, UserMemoryDeletionRepository deletions, UserMemoryService memories,
                        ConversationSummaryService summaries, AnalysisModelClient model, TextEmbeddingClient embeddings,
                        UserMemoryVectorStore vectors) {
        this.properties = properties; this.turns = turns; this.conversations = conversations; this.versions = versions;
        this.deletions = deletions; this.memories = memories; this.summaries = summaries; this.model = model;
        this.embeddings = embeddings; this.vectors = vectors;
    }
    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() {
        if (!properties.getWorkers().isEnabled() || !properties.getMemory().isEnabled()) return;
        turns.findTop10ByExtractionStatusOrderByUpdatedAtAsc(MemoryExtractionStatus.QUEUED).forEach(value -> processExtraction(value.getId()));
        conversations.findTop10BySummaryStatusOrderByUpdatedAtAsc(ConversationSummaryStatus.QUEUED).forEach(value -> processSummary(value.getId()));
        versions.findTop10ByIndexStatusOrderByCreatedAtAsc(MemoryIndexStatus.QUEUED).forEach(value -> processIndex(value.getId()));
        deletions.findTop10ByStatusOrderByUpdatedAtAsc(MemoryIndexStatus.QUEUED).forEach(value -> processDeletion(value.getId()));
    }
    public void processExtraction(String turnId) {
        if (!properties.getMemory().isEnabled()) return;
        UserMemoryService.ExtractionWork work = memories.claimExtraction(turnId); if (work == null) return;
        long started = System.nanoTime();
        try { memories.completeExtraction(turnId, model.complete(memories.extractionPrompt(work.userMessage())), properties.getDashscope().getChatModel(), elapsed(started)); }
        catch (RuntimeException exception) { memories.failExtraction(turnId, code(exception), exception.getMessage(), properties.getDashscope().getChatModel(), elapsed(started)); }
    }
    public void processSummary(String conversationId) {
        if (!properties.getMemory().isEnabled()) return;
        ConversationSummaryService.SummaryWork work = summaries.claim(conversationId); if (work == null) return;
        long started = System.nanoTime();
        try { summaries.complete(conversationId, work.throughTurn(), model.complete(work.prompt()), properties.getDashscope().getChatModel(), elapsed(started)); }
        catch (RuntimeException exception) { summaries.fail(conversationId, code(exception), exception.getMessage(), properties.getDashscope().getChatModel(), elapsed(started)); }
    }
    public void processIndex(String versionId) {
        if (!properties.getMemory().isEnabled()) return;
        UserMemoryService.IndexWork work = memories.claimIndex(versionId); if (work == null) return;
        try {
            vectors.ensureCollection(); vectors.deleteMemory(work.ownerId(), work.memoryId());
            vectors.upsert(work.ownerId(), work.memoryId(), work.versionId(), work.category(), work.content(), embeddings.embedDocuments(java.util.List.of(work.content())).get(0));
            memories.completeIndex(versionId);
        } catch (RuntimeException exception) { memories.failIndex(versionId, exception.getMessage()); }
    }
    public void processDeletion(String deletionId) {
        if (!properties.getMemory().isEnabled()) return;
        UserMemoryService.DeletionWork work = memories.claimDeletion(deletionId); if (work == null) return;
        try { vectors.ensureCollection(); vectors.deleteMemory(work.ownerId(), work.memoryId()); memories.completeDeletion(deletionId); }
        catch (RuntimeException exception) { memories.failDeletion(deletionId, exception.getMessage()); }
    }
    private static long elapsed(long started) { return java.time.Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private static String code(RuntimeException exception) { return exception instanceof ProviderException value ? value.getCode() : exception.getClass().getSimpleName(); }
}
