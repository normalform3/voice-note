package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.DocumentOverviewTool;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.QaRetrievalMode;
import com.voicenote.repository.KnowledgeIndexVersionRepository;
import com.voicenote.repository.OrganizedDocumentBlockRepository;
import com.voicenote.repository.OrganizedDocumentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentOverviewToolTest {
    @Test
    void readsTheFormalOverviewFrozenIntoTheRunInsteadOfTheLatestDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var versions = mock(KnowledgeIndexVersionRepository.class); var documents = mock(OrganizedDocumentRepository.class);
        var blocks = mock(OrganizedDocumentBlockRepository.class);
        var frozen = mapper.readTree("{\"title\":\"冻结标题\",\"summary\":\"冻结摘要\",\"topicCount\":1,\"topics\":[{\"title\":\"主题\",\"content\":\"结论\",\"startMs\":0,\"endMs\":1000,\"sourceFragments\":[{\"segmentId\":\"segment-1\",\"speakerId\":\"S1\",\"startMs\":0,\"endMs\":1000,\"text\":\"原文证据\"}]}]}");
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("document_overview"), false);
        AgentExecutionContext.ScopeDocument scope = new AgentExecutionContext.ScopeDocument("task", null, null,
                "formal-1", 3L, QaRetrievalMode.FORMAL_OVERVIEW, frozen, "标题", Instant.now(), "OTHER", null,
                List.of(), 2, mapper.createObjectNode());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT,
                ZoneId.of("Asia/Shanghai"), skill, List.of(scope), Instant.now().plusSeconds(10));

        var result = new DocumentOverviewTool(mapper, versions, documents, blocks).execute(context, mapper.readTree("{}"));

        assertThat(result.payload().path("overviews").path(0).path("title").asText()).isEqualTo("冻结标题");
        assertThat(context.evidence().all()).hasSize(1);
        verifyNoInteractions(versions, documents, blocks);
    }
}
