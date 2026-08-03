package com.voicenote.web;

import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.KnowledgeDocumentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge-documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documents;
    public KnowledgeDocumentController(KnowledgeDocumentService documents) { this.documents = documents; }
    @GetMapping List<DocumentView> list(Authentication authentication) { return documents.ownedDocuments(CurrentUser.require(authentication).id()).stream().map(DocumentView::from).toList(); }
    @PostMapping("/{documentId}/retry") void retry(@PathVariable String documentId, Authentication authentication) { documents.retry(CurrentUser.require(authentication).id(), documentId); }
    public record DocumentView(String id, String transcriptionTaskId, String title, String status, String failureMessage, Instant updatedAt) {
        static DocumentView from(KnowledgeDocument document) { return new DocumentView(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), document.getStatus().name(), document.getFailureMessage(), document.getUpdatedAt()); }
    }
}
