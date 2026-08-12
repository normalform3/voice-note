package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.AgentConversationRepository;
import com.voicenote.repository.AgentConversationTurnRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationContextServiceTest {
    @Test
    void includesOnlyCompletedEarlierTurnsFromTheOwnedConversation() {
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentConversationTurnRepository turns = mock(AgentConversationTurnRepository.class);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        AgentConversation conversation = new AgentConversation("owner-a", "会话", AgentScopeType.CURRENT_DOCUMENT,
                "Asia/Shanghai", "knowledge-qa", "v1", null, "{}", "hash", true);
        AgentConversationTurn earlier = new AgentConversationTurn(conversation.getId(), "owner-a", 0, "我之前问了什么？");
        KnowledgeRun earlierRun = run("owner-a", "旧问题");
        earlierRun.succeed("{\"answer\":\"这是已完成的旧回答\"}");
        earlier.attachRun(earlierRun.getId());
        AgentConversationTurn currentTurn = new AgentConversationTurn(conversation.getId(), "owner-a", 1, "请继续");
        KnowledgeRun current = run("owner-a", "请继续");
        current.useConversation(conversation.getId(), 1, true);
        currentTurn.attachRun(current.getId());

        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(turns.findByConversationIdOrderByTurnIndexAsc(conversation.getId())).thenReturn(List.of(earlier, currentTurn));
        when(runs.findById(earlierRun.getId())).thenReturn(Optional.of(earlierRun));

        AgentConversationContextService service = new AgentConversationContextService(conversations, turns, runs,
                new AppProperties(), new ObjectMapper());

        String context = service.contextFor(current);
        assertThat(context).contains("我之前问了什么？", "这是已完成的旧回答").doesNotContain("请继续");

        KnowledgeRun otherOwner = run("owner-b", "越权读取");
        otherOwner.useConversation(conversation.getId(), 2, true);
        assertThat(service.contextFor(otherOwner)).isNull();
    }

    @Test
    void keepsWholeRecentBlocksWhenTheContextLimitIsReached() {
        AgentConversationRepository conversations = mock(AgentConversationRepository.class);
        AgentConversationTurnRepository turns = mock(AgentConversationTurnRepository.class);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        AgentConversation conversation = new AgentConversation("owner", "会话", AgentScopeType.CURRENT_DOCUMENT,
                "Asia/Shanghai", "knowledge-qa", "v1", null, "{}", "hash", true);
        AgentConversationTurn oldTurn = new AgentConversationTurn(conversation.getId(), "owner", 0, "旧".repeat(2_000));
        AgentConversationTurn recentTurn = new AgentConversationTurn(conversation.getId(), "owner", 1, "最近问题");
        KnowledgeRun current = run("owner", "继续"); current.useConversation(conversation.getId(), 2, true);
        when(conversations.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(turns.findByConversationIdOrderByTurnIndexAsc(conversation.getId())).thenReturn(List.of(oldTurn, recentTurn));
        AppProperties properties = new AppProperties(); properties.getMemory().setContextMaxCharacters(1_000);

        String context = new AgentConversationContextService(conversations, turns, runs, properties, new ObjectMapper()).contextFor(current);

        assertThat(context).hasSizeLessThanOrEqualTo(1_000).contains("Turn 2 用户：最近问题");
    }

    private static KnowledgeRun run(String owner, String question) {
        return new KnowledgeRun(owner, question, "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                "knowledge-qa", "v1", null, "{}", "hash", 4, 4, 4, 60_000);
    }
}
