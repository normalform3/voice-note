package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.AgentConversationRepository;
import com.voicenote.repository.AgentConversationTurnRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
public class ConversationSummaryService {
    public static final String PROMPT_VERSION = "conversation-summary-v1";
    private final AgentConversationRepository conversations;
    private final AgentConversationTurnRepository turns;
    private final KnowledgeRunRepository runs;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final String promptTemplate;
    public ConversationSummaryService(AgentConversationRepository conversations, AgentConversationTurnRepository turns,
                                      KnowledgeRunRepository runs, AppProperties properties, ObjectMapper mapper) {
        this.conversations = conversations; this.turns = turns; this.runs = runs; this.properties = properties; this.mapper = mapper;
        try { this.promptTemplate = new ClassPathResource("prompts/" + PROMPT_VERSION + ".md").getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception exception) { throw new IllegalStateException("Cannot load versioned conversation summary prompt", exception); }
    }
    @Transactional
    public SummaryWork claim(String conversationId) {
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null);
        if (conversation == null || !conversation.beginSummary()) return null;
        List<AgentConversationTurn> completed = turns.findByConversationIdOrderByTurnIndexAsc(conversationId).stream()
                .filter(value -> value.getTurnIndex() > conversation.getSummaryThroughTurn())
                .filter(value -> value.getKnowledgeRunId() != null)
                .filter(value -> runs.findById(value.getKnowledgeRunId()).map(KnowledgeRun::isTerminal).orElse(false)).toList();
        if (completed.isEmpty()) { conversation.completeSummary(Objects.toString(conversation.getRollingSummary(), ""), conversation.getSummaryThroughTurn()); conversations.save(conversation); return null; }
        int targetPosition = Math.max(0, completed.size() - Math.max(1, properties.getMemory().getRecentTurns()) - 1);
        int through = completed.get(targetPosition).getTurnIndex();
        List<AgentConversationTurn> source = completed.stream().filter(value -> value.getTurnIndex() <= through).toList();
        StringBuilder transcript = new StringBuilder();
        if (conversation.getRollingSummary() != null) transcript.append("已有摘要：\n").append(conversation.getRollingSummary()).append("\n\n");
        for (AgentConversationTurn turn : source) {
            transcript.append("用户：").append(turn.getUserMessage()).append('\n');
            runs.findById(turn.getKnowledgeRunId()).map(this::answer).filter(value -> !value.isBlank())
                    .ifPresent(value -> transcript.append("Agent：").append(value).append("\n\n"));
        }
        conversations.save(conversation);
        String prompt = promptTemplate.replace("{{MAX_CHARACTERS}}", Integer.toString(properties.getMemory().getSummaryMaxCharacters()))
                .replace("{{TRANSCRIPT}}", transcript);
        return new SummaryWork(conversationId, through, prompt, conversation.getSummaryAttempts());
    }
    @Transactional public void complete(String conversationId, int through, String summary) { complete(conversationId, through, summary, null, null); }
    @Transactional public void complete(String conversationId, int through, String summary, String modelId, Long durationMs) {
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null); if (conversation == null) return;
        String normalized = Objects.toString(summary, "").trim(); int max = properties.getMemory().getSummaryMaxCharacters();
        if (normalized.length() > max) normalized = normalized.substring(0, max);
        conversation.completeSummary(normalized, through, modelId, durationMs); conversations.save(conversation);
    }
    @Transactional public void fail(String conversationId, String message) {
        fail(conversationId, "CONVERSATION_SUMMARY_FAILED", message, null, null);
    }
    @Transactional public void fail(String conversationId, String code, String message, String modelId, Long durationMs) {
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null); if (conversation == null) return;
        conversation.failSummary(code, shorten(message), modelId, durationMs,
                conversation.getSummaryAttempts() < properties.getMemory().getMaxAttempts()); conversations.save(conversation);
    }
    private String answer(KnowledgeRun run) {
        if (run.getResultDocument() == null) return "";
        try { JsonNode node = mapper.readTree(run.getResultDocument()); return node.path("answer").isTextual() ? node.path("answer").asText() : node.toString(); }
        catch (Exception exception) { return run.getResultDocument(); }
    }
    private static String shorten(String value) { String output = Objects.toString(value, "Summary failed").replaceAll("[\\r\\n]+", " "); return output.substring(0, Math.min(1000, output.length())); }
    public record SummaryWork(String conversationId, int throughTurn, String prompt, int attempt) { }
}
