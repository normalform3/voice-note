package com.voicenote.repository;

import com.voicenote.domain.AgentConversationTurn;
import com.voicenote.domain.MemoryExtractionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentConversationTurnRepository extends JpaRepository<AgentConversationTurn, String> {
    List<AgentConversationTurn> findByConversationIdOrderByTurnIndexAsc(String conversationId);
    List<AgentConversationTurn> findTop10ByExtractionStatusOrderByUpdatedAtAsc(MemoryExtractionStatus status);
    Optional<AgentConversationTurn> findByKnowledgeRunId(String runId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from AgentConversationTurn value where value.id = :id")
    Optional<AgentConversationTurn> findByIdForUpdate(@Param("id") String id);
    void deleteByConversationId(String conversationId);
    List<AgentConversationTurn> findByIdIn(Collection<String> ids);
}
