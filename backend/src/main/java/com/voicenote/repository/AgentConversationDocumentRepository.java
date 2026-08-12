package com.voicenote.repository;

import com.voicenote.domain.AgentConversationDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentConversationDocumentRepository extends JpaRepository<AgentConversationDocument, String> {
    List<AgentConversationDocument> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    List<AgentConversationDocument> findByTranscriptionTaskId(String taskId);
    void deleteByConversationId(String conversationId);
}
