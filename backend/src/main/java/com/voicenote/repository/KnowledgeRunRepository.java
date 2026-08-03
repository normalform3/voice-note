package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRun;
import com.voicenote.domain.KnowledgeRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunRepository extends JpaRepository<KnowledgeRun, String> {
    List<KnowledgeRun> findTop10ByStatusOrderByCreatedAtAsc(KnowledgeRunStatus status);
    List<KnowledgeRun> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
