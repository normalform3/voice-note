package com.voicenote.repository;

import com.voicenote.domain.AgentConversation;
import com.voicenote.domain.ConversationSummaryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, String> {
    Page<AgentConversation> findByOwnerIdOrderByUpdatedAtDesc(String ownerId, Pageable pageable);
    List<AgentConversation> findTop10BySummaryStatusOrderByUpdatedAtAsc(ConversationSummaryStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select conversation from AgentConversation conversation where conversation.id = :id")
    Optional<AgentConversation> findByIdForUpdate(@Param("id") String id);
}
