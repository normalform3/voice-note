package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@Service
public class AgentConversationService {
    private static final String CREATE_TURN_OPERATION = "CREATE_AGENT_CONVERSATION_TURN";
    private final AgentConversationRepository conversations;
    private final AgentConversationDocumentRepository conversationDocuments;
    private final AgentConversationTurnRepository turns;
    private final UserMemoryCandidateRepository candidates;
    private final KnowledgeRunRepository runRepository;
    private final KnowledgeAgentService agents;
    private final AgentSkillRegistry skills;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public AgentConversationService(AgentConversationRepository conversations, AgentConversationDocumentRepository conversationDocuments,
                                    AgentConversationTurnRepository turns, UserMemoryCandidateRepository candidates,
                                    KnowledgeRunRepository runRepository, KnowledgeAgentService agents, AgentSkillRegistry skills,
                                    IdempotencyService idempotency, OutboxService outbox, AppProperties properties, ObjectMapper mapper) {
        this.conversations = conversations; this.conversationDocuments = conversationDocuments; this.turns = turns;
        this.candidates = candidates; this.runRepository = runRepository; this.agents = agents; this.skills = skills;
        this.idempotency = idempotency; this.outbox = outbox; this.properties = properties; this.mapper = mapper;
    }

    @Transactional
    public ConversationView create(String ownerId, CreateConversationCommand command) {
        if (!properties.getAgent().isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_DISABLED", "The bounded Agent runtime is disabled");
        if (!properties.getMemory().isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_MEMORY_DISABLED", "Agent conversations and memory are disabled");
        KnowledgeAgentService.AgentScopeCommand scope = new KnowledgeAgentService.AgentScopeCommand(command.scopeType(), command.transcriptionTaskIds());
        List<String> taskIds = agents.resolveScopeTaskIds(ownerId, scope);
        String zone = normalizeZone(command.timeZone());
        String skillId = "auto", skillVersion = "pending", skillVersionId = null, snapshot = null, hash = null;
        if (command.skillId() != null && !command.skillId().isBlank()) {
            AgentSkill selected;
            try { selected = skills.require(ownerId, command.skillId()); }
            catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SKILL_NOT_FOUND", exception.getMessage()); }
            try { snapshot = mapper.writeValueAsString(selected); }
            catch (Exception exception) { throw new IllegalStateException("Cannot snapshot conversation Skill", exception); }
            skillId = selected.id(); skillVersion = selected.version(); skillVersionId = selected.versionId(); hash = Hashing.sha256(snapshot);
        }
        String title = command.title() == null || command.title().isBlank() ? "新会话" : command.title().trim();
        if (title.length() > 160) throw new ApiException(HttpStatus.BAD_REQUEST, "CONVERSATION_TITLE_INVALID", "title must contain at most 160 characters");
        AgentConversation conversation = conversations.save(new AgentConversation(ownerId, title, command.scopeType(), zone,
                skillId, skillVersion, skillVersionId, snapshot, hash, properties.getMemory().isEnabled() && command.memoryEnabled()));
        taskIds.forEach(taskId -> conversationDocuments.save(new AgentConversationDocument(conversation.getId(), taskId)));
        return view(conversation);
    }

    @Transactional(readOnly = true)
    public Page<ConversationView> list(String ownerId, int page, int size) {
        return conversations.findByOwnerIdOrderByUpdatedAtDesc(ownerId, PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size))))
                .map(this::view);
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(String ownerId, String conversationId) {
        AgentConversation conversation = owned(ownerId, conversationId);
        List<TurnView> values = turns.findByConversationIdOrderByTurnIndexAsc(conversationId).stream().map(this::turnView).toList();
        return new ConversationDetail(view(conversation), taskIds(conversationId), values);
    }

    @Transactional
    public KnowledgeAgentService.AgentRunView createTurn(String ownerId, String key, String conversationId, String message) {
        if (!properties.getMemory().isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_MEMORY_DISABLED", "Agent conversations and memory are disabled");
        String normalized = message == null ? "" : message.trim();
        if (normalized.isBlank() || normalized.length() > 8000) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_QUESTION", "message must contain 1 to 8000 characters");
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId)
                .filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_CONVERSATION_NOT_FOUND", "Agent conversation was not found"));
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_TURN_OPERATION, key,
                Hashing.canonicalJsonHash(Map.of("conversationId", conversationId, "message", normalized)));
        if (record.getResourceId() != null) return agents.agentRunView(agents.ownedRun(ownerId, record.getResourceId()));
        if (conversation.getStatus() != AgentConversationStatus.ACTIVE) throw new ApiException(HttpStatus.CONFLICT, "AGENT_CONVERSATION_ARCHIVED", "Archived conversations cannot accept new turns");
        List<KnowledgeRun> priorRuns = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        boolean running = priorRuns.stream().anyMatch(value -> !value.isTerminal());
        if (running) throw new ApiException(HttpStatus.CONFLICT, "AGENT_CONVERSATION_BUSY", "Wait for the current conversation turn to finish");
        if ("pending".equals(conversation.getSkillVersion())) {
            priorRuns.stream().filter(KnowledgeRun::isTerminal).filter(value -> !"pending".equals(value.getSkillVersion()))
                    .reduce((first, second) -> second)
                    .ifPresent(value -> conversation.freezeSkill(value.getSkillId(), value.getSkillVersion(), value.getSkillVersionId(), value.getSkillSnapshot(), value.getSkillHash()));
        }
        int turnIndex = conversation.allocateTurn(); conversation.useFirstQuestionAsTitle(normalized); conversations.save(conversation);
        AgentConversationTurn turn = turns.save(new AgentConversationTurn(conversationId, ownerId, turnIndex, normalized));
        List<String> taskIds = taskIds(conversationId);
        KnowledgeAgentService.CreateAgentCommand command = new KnowledgeAgentService.CreateAgentCommand(normalized,
                new KnowledgeAgentService.AgentScopeCommand(conversation.getScopeType(), taskIds, true),
                "pending".equals(conversation.getSkillVersion()) ? null : conversation.getSkillId(), conversation.getTimeZone(),
                conversationId, turnIndex, conversation.isMemoryEnabled(), conversation.getSkillSnapshot());
        KnowledgeRun run = agents.createAgent(ownerId, Hashing.sha256("conversation-run:" + key), command);
        turn.attachRun(run.getId()); turns.save(turn);
        try { idempotency.complete(record, run.getId(), mapper.writeValueAsString(Map.of("runId", run.getId()))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist conversation Turn idempotency response", exception); }
        return agents.agentRunView(run);
    }

    @Transactional
    public ConversationView update(String ownerId, String conversationId, UpdateConversationCommand command) {
        AgentConversation conversation = conversations.findByIdForUpdate(conversationId)
                .filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_CONVERSATION_NOT_FOUND", "Agent conversation was not found"));
        if (command.title() != null && command.title().trim().length() > 160) throw new ApiException(HttpStatus.BAD_REQUEST, "CONVERSATION_TITLE_INVALID", "title must contain at most 160 characters");
        Boolean memoryEnabled = command.memoryEnabled() == null ? null : properties.getMemory().isEnabled() && command.memoryEnabled();
        conversation.update(command.title(), command.status(), memoryEnabled); return view(conversations.save(conversation));
    }

    @Transactional
    public void delete(String ownerId, String conversationId) {
        AgentConversation conversation = owned(ownerId, conversationId);
        List<AgentConversationTurn> conversationTurns = turns.findByConversationIdOrderByTurnIndexAsc(conversationId);
        List<String> turnIds = conversationTurns.stream().map(AgentConversationTurn::getId).toList();
        if (!turnIds.isEmpty()) candidates.deleteBySourceTurnIdInAndStatusNot(turnIds, UserMemoryCandidateStatus.CONFIRMED);
        conversationTurns.forEach(turn -> { if (turn.getKnowledgeRunId() != null) turn.detachRun(); });
        turns.saveAll(conversationTurns);
        List<KnowledgeRun> conversationRuns = runRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        Set<String> conversationRunIds = conversationRuns.stream().map(KnowledgeRun::getId).collect(java.util.stream.Collectors.toSet());
        for (KnowledgeRun run : conversationRuns) {
            if (run.getParentRunId() == null || !conversationRunIds.contains(run.getParentRunId())) agents.deleteRunGraph(ownerId, run.getId());
        }
        turns.deleteByConversationId(conversationId);
        conversationDocuments.deleteByConversationId(conversationId);
        outbox.deleteAggregate("agent_conversation", conversationId);
        conversations.delete(conversation);
    }

    @Transactional
    public void deleteConversationsUsingTask(String ownerId, String taskId) {
        conversationDocuments.findByTranscriptionTaskId(taskId).stream().map(AgentConversationDocument::getConversationId).distinct()
                .filter(id -> conversations.findById(id).map(value -> value.getOwnerId().equals(ownerId)).orElse(false))
                .forEach(id -> delete(ownerId, id));
    }

    private AgentConversation owned(String ownerId, String id) {
        return conversations.findById(id).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_CONVERSATION_NOT_FOUND", "Agent conversation was not found"));
    }
    private List<String> taskIds(String conversationId) {
        return conversationDocuments.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(AgentConversationDocument::getTranscriptionTaskId).toList();
    }
    private ConversationView view(AgentConversation value) {
        return new ConversationView(value.getId(), value.getTitle(), value.getStatus(), value.getScopeType(), value.getTimeZone(),
                value.getSkillId(), value.getSkillVersion(), value.isMemoryEnabled(), value.getSummaryStatus(),
                value.getSummaryFailureMessage(), value.getCreatedAt(), value.getUpdatedAt());
    }
    private TurnView turnView(AgentConversationTurn value) {
        KnowledgeRun run = value.getKnowledgeRunId() == null ? null : runRepository.findById(value.getKnowledgeRunId()).orElse(null);
        return new TurnView(value.getId(), value.getTurnIndex(), value.getUserMessage(), value.getKnowledgeRunId(),
                run == null ? null : run.getStatus(), run == null ? null : run.getResultDocument(), run == null ? null : run.getFailureMessage(),
                value.getExtractionStatus(), value.getExtractionFailureMessage(), value.getCreatedAt());
    }
    private static String normalizeZone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Shanghai" : value).getId(); }
        catch (DateTimeException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_ZONE", "timeZone must be a valid IANA zone"); }
    }

    public record CreateConversationCommand(String title, AgentScopeType scopeType, List<String> transcriptionTaskIds,
                                            String skillId, String timeZone, boolean memoryEnabled) { }
    public record UpdateConversationCommand(String title, AgentConversationStatus status, Boolean memoryEnabled) { }
    public record ConversationView(String id, String title, AgentConversationStatus status, AgentScopeType scopeType,
                                   String timeZone, String skillId, String skillVersion, boolean memoryEnabled,
                                   ConversationSummaryStatus summaryStatus, String summaryFailureMessage,
                                   java.time.Instant createdAt, java.time.Instant updatedAt) { }
    public record TurnView(String id, int turnIndex, String userMessage, String runId, KnowledgeRunStatus runStatus,
                           String resultDocument, String failureMessage, MemoryExtractionStatus extractionStatus,
                           String extractionFailureMessage, java.time.Instant createdAt) { }
    public record ConversationDetail(ConversationView conversation, List<String> transcriptionTaskIds, List<TurnView> turns) { }
}
