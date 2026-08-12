package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMemoryServiceTest {
    @Test
    void extractionAcceptsOnlyVerifiableNonSensitiveDirectStatements() {
        Fixture fixture = fixture();
        String message = "我偏好简洁的回答。我的 API key 是 abc。我的健康状况很好。我同事的电话号码是 123。";
        AgentConversationTurn turn = new AgentConversationTurn("conversation", "owner", 0, message);
        when(fixture.turns.findById(turn.getId())).thenReturn(Optional.of(turn));
        when(fixture.users.findByIdForUpdate("owner")).thenReturn(Optional.of(mock(UserAccount.class)));
        when(fixture.candidates.countByOwnerIdAndStatus("owner", UserMemoryCandidateStatus.PENDING)).thenReturn(0L);
        when(fixture.candidates.findByOwnerIdAndSourceTurnIdAndSemanticKey(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(fixture.candidates.findFirstByOwnerIdAndSemanticKeyAndStatusOrderByCreatedAtDesc(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(fixture.memories.findByOwnerIdAndSemanticKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(fixture.candidates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.completeExtraction(turn.getId(), """
                {"candidates":[
                  {"category":"PREFERENCE","semanticKey":"answer-style","content":"用户偏好简洁的回答","sourceExcerpt":"我偏好简洁的回答","confidence":0.96},
                  {"category":"PROFILE","semanticKey":"credential","content":"我的 API key 是 abc","sourceExcerpt":"我的 API key 是 abc","confidence":0.99},
                  {"category":"PROFILE","semanticKey":"health","content":"我的健康状况很好","sourceExcerpt":"我的健康状况很好","confidence":0.99},
                  {"category":"PROFILE","semanticKey":"coworker-phone","content":"我同事的电话号码是 123","sourceExcerpt":"我同事的电话号码是 123","confidence":0.99},
                  {"category":"WORK_STYLE","semanticKey":"low-confidence","content":"用户重视效率","sourceExcerpt":"我偏好简洁的回答","confidence":0.50},
                  {"category":"PROFILE","semanticKey":"invented","content":"用户住在上海","sourceExcerpt":"我住在上海","confidence":0.99}
                ]}
                """, "model", 12L);

        ArgumentCaptor<UserMemoryCandidate> captor = ArgumentCaptor.forClass(UserMemoryCandidate.class);
        verify(fixture.candidates).save(captor.capture());
        assertThat(captor.getValue().getSemanticKey()).isEqualTo("answer-style");
        assertThat(captor.getValue().getStatus()).isEqualTo(UserMemoryCandidateStatus.PENDING);
        assertThat(turn.getExtractionStatus()).isEqualTo(MemoryExtractionStatus.SUCCEEDED);
    }

    @Test
    void vectorHitsAreRevalidatedAgainstOwnerAndCurrentMysqlVersion() {
        Fixture fixture = fixture();
        UserMemory memory = new UserMemory("owner-a", UserMemoryCategory.WORK_STYLE, "answer-style");
        UserMemoryVersion current = new UserMemoryVersion(memory.getId(), 2, "偏好简洁回答", null);
        memory.useVersion(current.getId());
        when(fixture.memories.findByIdInAndOwnerIdAndStatus(anyList(), eq("owner-a"), eq(UserMemoryStatus.ACTIVE)))
                .thenReturn(List.of(memory));
        when(fixture.versions.findById(current.getId())).thenReturn(Optional.of(current));

        List<UserMemoryService.SearchResult> results = fixture.service.validateHits("owner-a", List.of(
                new UserMemoryVectorStore.MemoryHit(memory.getId(), current.getId(), 0.9),
                new UserMemoryVectorStore.MemoryHit(memory.getId(), "stale-version", 0.8),
                new UserMemoryVectorStore.MemoryHit("other-owner-memory", "other-version", 0.99)));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.memoryId()).isEqualTo(memory.getId());
            assertThat(result.versionId()).isEqualTo(current.getId());
            assertThat(result.content()).isEqualTo("偏好简洁回答");
        });
    }

    private static Fixture fixture() {
        UserMemoryCandidateRepository candidates = mock(UserMemoryCandidateRepository.class);
        UserMemoryRepository memories = mock(UserMemoryRepository.class);
        UserMemoryVersionRepository versions = mock(UserMemoryVersionRepository.class);
        UserMemoryDeletionRepository deletions = mock(UserMemoryDeletionRepository.class);
        AgentConversationTurnRepository turns = mock(AgentConversationTurnRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        AppProperties properties = new AppProperties(); properties.getMemory().setEnabled(true);
        UserMemoryService service = new UserMemoryService(candidates, memories, versions, deletions, turns, users,
                mock(OutboxService.class), mock(ProgressEventPublisher.class), properties, new ObjectMapper());
        return new Fixture(service, candidates, memories, versions, turns, users);
    }

    private record Fixture(UserMemoryService service, UserMemoryCandidateRepository candidates,
                           UserMemoryRepository memories, UserMemoryVersionRepository versions,
                           AgentConversationTurnRepository turns, UserAccountRepository users) { }
}
