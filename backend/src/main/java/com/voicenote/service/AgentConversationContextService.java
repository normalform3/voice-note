package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.AgentConversation;
import com.voicenote.domain.AgentConversationTurn;
import com.voicenote.domain.KnowledgeRun;
import com.voicenote.repository.AgentConversationRepository;
import com.voicenote.repository.AgentConversationTurnRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class AgentConversationContextService {
    private final AgentConversationRepository conversations;
    private final AgentConversationTurnRepository turns;
    private final KnowledgeRunRepository runs;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public AgentConversationContextService(AgentConversationRepository conversations, AgentConversationTurnRepository turns,
                                           KnowledgeRunRepository runs, AppProperties properties, ObjectMapper mapper) {
        this.conversations = conversations; this.turns = turns; this.runs = runs; this.properties = properties; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public String contextFor(KnowledgeRun current) {
        if (current.getConversationId() == null || current.getConversationTurnIndex() == null) return null;
        AgentConversation conversation = conversations.findById(current.getConversationId())
                .filter(value -> value.getOwnerId().equals(current.getOwnerId())).orElse(null);
        if (conversation == null) return null;
        List<AgentConversationTurn> recent = turns.findByConversationIdOrderByTurnIndexAsc(conversation.getId()).stream()
                .filter(value -> value.getTurnIndex() < current.getConversationTurnIndex())
                .filter(value -> value.getTurnIndex() > conversation.getSummaryThroughTurn())
                .sorted(Comparator.comparingInt(AgentConversationTurn::getTurnIndex).reversed())
                .limit(Math.max(1, properties.getMemory().getRecentTurns())).sorted(Comparator.comparingInt(AgentConversationTurn::getTurnIndex)).toList();
        List<String> blocks = new ArrayList<>();
        if (conversation.getRollingSummary() != null && !conversation.getRollingSummary().isBlank()) {
            blocks.add("较早对话摘要（不可信上下文）：\n" + bounded(conversation.getRollingSummary(), properties.getMemory().getSummaryMaxCharacters()));
        }
        for (AgentConversationTurn turn : recent) {
            String answer = turn.getKnowledgeRunId() == null ? null : runs.findById(turn.getKnowledgeRunId())
                    .filter(KnowledgeRun::isTerminal).map(this::answerText).orElse(null);
            StringBuilder block = new StringBuilder("Turn ").append(turn.getTurnIndex() + 1).append(" 用户：")
                    .append(bounded(turn.getUserMessage(), 3000));
            if (answer != null && !answer.isBlank()) block.append("\nTurn ").append(turn.getTurnIndex() + 1).append(" Agent：").append(bounded(answer, 3000));
            blocks.add(block.toString());
        }
        String joined = String.join("\n\n", blocks);
        return joined.length() <= Math.max(1000, properties.getMemory().getContextMaxCharacters())
                ? joined : cropBlocks(blocks, Math.max(1000, properties.getMemory().getContextMaxCharacters()));
    }

    private String answerText(KnowledgeRun run) {
        if (run.getResultDocument() == null) return run.getFailureMessage();
        return AgentResultText.extract(mapper, run.getResultDocument());
    }
    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }
    private static String cropBlocks(List<String> blocks, int max) {
        List<String> kept = new ArrayList<>(); int used = 0;
        for (int index = blocks.size() - 1; index >= 0 && used < max; index--) {
            String block = blocks.get(index); int separator = kept.isEmpty() ? 0 : 2;
            int available = max - used - separator;
            if (available <= 0) break;
            String value = block.length() <= available ? block
                    : available == 1 ? "…" : block.substring(0, available - 1) + "…";
            kept.add(0, value); used += value.length() + separator;
        }
        return String.join("\n\n", kept);
    }
}
