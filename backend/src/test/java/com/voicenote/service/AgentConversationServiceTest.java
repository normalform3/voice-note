package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentConversationServiceTest {
    @Test
    void rejectsASecondTurnWhileTheConversationHasAnUnsettledRun() {
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AgentConversation conversation = new AgentConversation("owner", "会话", AgentScopeType.ALL_DOCUMENTS,
                "Asia/Shanghai", "knowledge-qa", "v1", null, "{}", "hash", true);
        KnowledgeRun running = new KnowledgeRun("owner", "处理中", "model", 4);
        when(conversations.findByIdForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));
        when(idempotency.reserve(eq("owner"), anyString(), eq("turn-key"), anyString()))
                .thenReturn(new IdempotencyRecord("owner", "CREATE_AGENT_CONVERSATION_TURN", "turn-key", "a".repeat(64)));
        when(runs.findByConversationIdOrderByCreatedAtAsc(conversation.getId())).thenReturn(List.of(running));
        KnowledgeAgentService agents = mock(KnowledgeAgentService.class);
        AgentConversationService service = service(conversations, runs, idempotency, agents);

        assertThatThrownBy(() -> service.createTurn("owner", "turn-key", conversation.getId(), "继续追问"))
                .isInstanceOf(ApiException.class).hasMessageContaining("current conversation turn");
        verify(agents, never()).createAgent(anyString(), anyString(), any());
    }

    @Test
    void returnsNotFoundBeforeReadingAnotherOwnersConversation() {
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AgentConversation conversation = new AgentConversation("owner-a", "会话", AgentScopeType.ALL_DOCUMENTS,
                "Asia/Shanghai", "knowledge-qa", "v1", null, "{}", "hash", true);
        when(conversations.findByIdForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));

        AgentConversationService service = service(conversations, runs, idempotency, mock(KnowledgeAgentService.class));

        assertThatThrownBy(() -> service.createTurn("owner-b", "turn-key", conversation.getId(), "越权"))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");
        verifyNoInteractions(idempotency, runs);
    }

    private static AgentConversationService service(AgentConversationRepository conversations, KnowledgeRunRepository runs,
                                                     IdempotencyService idempotency, KnowledgeAgentService agents) {
        AppProperties properties = new AppProperties(); properties.getAgent().setEnabled(true); properties.getMemory().setEnabled(true);
        return new AgentConversationService(conversations, mock(AgentConversationDocumentRepository.class),
                mock(AgentConversationTurnRepository.class), mock(UserMemoryCandidateRepository.class), runs, agents,
                mock(AgentSkillRegistry.class), idempotency, mock(OutboxService.class), properties,
                new ObjectMapper().findAndRegisterModules());
    }
}
