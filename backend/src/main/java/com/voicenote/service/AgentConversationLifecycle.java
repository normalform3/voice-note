package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.AgentConversationRepository;
import com.voicenote.repository.AgentConversationTurnRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class AgentConversationLifecycle {
    private final AgentConversationRepository conversations;
    private final AgentConversationTurnRepository turns;
    private final KnowledgeRunRepository runs;
    private final OutboxService outbox;
    private final AppProperties properties;

    public AgentConversationLifecycle(AgentConversationRepository conversations, AgentConversationTurnRepository turns,
                                      KnowledgeRunRepository runs, OutboxService outbox, AppProperties properties) {
        this.conversations = conversations; this.turns = turns; this.runs = runs; this.outbox = outbox; this.properties = properties;
    }

    /** Replays terminal Run settlement when the original after-run transaction failed before queuing follow-up work. */
    @Transactional
    public void recoverSettledTurns() {
        turns.findTop10ByExtractionStatusOrderByUpdatedAtAsc(MemoryExtractionStatus.NOT_REQUESTED).stream()
                .map(AgentConversationTurn::getKnowledgeRunId)
                .filter(java.util.Objects::nonNull)
                .filter(runId -> runs.findById(runId).map(KnowledgeRun::isTerminal).orElse(false))
                .forEach(this::settle);
    }

    @Transactional
    public void settle(String runId) {
        KnowledgeRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.isTerminal() || run.getConversationId() == null) return;
        AgentConversationTurn turn = turns.findByKnowledgeRunId(runId).orElse(null);
        if (turn == null) return;
        AgentConversation conversation = conversations.findByIdForUpdate(run.getConversationId()).orElse(null);
        if (conversation == null || !conversation.getOwnerId().equals(run.getOwnerId())) return;
        conversation.freezeSkill(run.getSkillId(), run.getSkillVersion(), run.getSkillVersionId(), run.getSkillSnapshot(), run.getSkillHash());
        if (properties.getMemory().isEnabled() && run.isMemoryEnabled()
                && turn.getExtractionStatus() == MemoryExtractionStatus.NOT_REQUESTED) {
            String inputHash = Hashing.canonicalJsonHash(Map.of("turnId", turn.getId(), "message", turn.getUserMessage(), "version", UserMemoryService.EXTRACTION_VERSION));
            turn.queueExtraction(inputHash, UserMemoryService.EXTRACTION_VERSION); turns.save(turn);
            outbox.enqueue("agent_conversation_turn", turn.getId(), EventType.MEMORY_EXTRACTION_REQUESTED,
                    "{\"turnId\":\"" + turn.getId() + "\"}", "memory-extract:" + inputHash);
        } else if (turn.getExtractionStatus() == MemoryExtractionStatus.NOT_REQUESTED) {
            turn.skipExtraction(); turns.save(turn);
        }
        queueSummaryIfNeeded(conversation);
        conversations.save(conversation);
    }

    private void queueSummaryIfNeeded(AgentConversation conversation) {
        if (conversation.getSummaryStatus() == ConversationSummaryStatus.QUEUED
                || conversation.getSummaryStatus() == ConversationSummaryStatus.RUNNING) return;
        if (conversation.getSummaryStatus() == ConversationSummaryStatus.FAILED
                && conversation.getSummaryAttempts() >= properties.getMemory().getMaxAttempts()) return;
        List<AgentConversationTurn> unsummarized = turns.findByConversationIdOrderByTurnIndexAsc(conversation.getId()).stream()
                .filter(value -> value.getTurnIndex() > conversation.getSummaryThroughTurn())
                .filter(value -> value.getKnowledgeRunId() != null)
                .filter(value -> runs.findById(value.getKnowledgeRunId()).map(KnowledgeRun::isTerminal).orElse(false)).toList();
        int characters = unsummarized.stream().mapToInt(value -> value.getUserMessage().length()
                + runs.findById(value.getKnowledgeRunId()).map(run -> run.getResultDocument() == null ? 0 : run.getResultDocument().length()).orElse(0)).sum();
        if (unsummarized.size() <= properties.getMemory().getRecentTurns()
                && characters <= properties.getMemory().getSummaryTriggerCharacters()) return;
        int through = unsummarized.get(Math.max(0, unsummarized.size() - properties.getMemory().getRecentTurns() - 1)).getTurnIndex();
        String inputHash = Hashing.canonicalJsonHash(Map.of("conversationId", conversation.getId(), "through", through,
                "priorSummary", java.util.Objects.toString(conversation.getRollingSummary(), ""), "prompt", ConversationSummaryService.PROMPT_VERSION));
        conversation.queueSummary(inputHash, ConversationSummaryService.PROMPT_VERSION);
        outbox.enqueue("agent_conversation", conversation.getId(), EventType.CONVERSATION_SUMMARY_REQUESTED,
                "{\"conversationId\":\"" + conversation.getId() + "\"}", "conversation-summary:" + inputHash);
    }
}
