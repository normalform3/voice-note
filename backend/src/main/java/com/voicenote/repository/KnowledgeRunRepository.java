package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRun;
import com.voicenote.domain.KnowledgeRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Collection;
import java.time.Instant;

public interface KnowledgeRunRepository extends JpaRepository<KnowledgeRun, String> {
    List<KnowledgeRun> findTop10ByStatusOrderByCreatedAtAsc(KnowledgeRunStatus status);
    List<KnowledgeRun> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<KnowledgeRun> findByParentRunIdOrderByCreatedAtAsc(String parentRunId);
    List<KnowledgeRun> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<KnowledgeRun> findTop10ByStatusAndLeaseUntilBeforeOrderByCreatedAtAsc(KnowledgeRunStatus status, Instant leaseUntil);
    boolean existsBySkillVersionIdInAndStatusIn(Collection<String> skillVersionIds, Collection<KnowledgeRunStatus> statuses);
    long countByOwnerIdAndSkillVersionNot(String ownerId, String skillVersion);
}
