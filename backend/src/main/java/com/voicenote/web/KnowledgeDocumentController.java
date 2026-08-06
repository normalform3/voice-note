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
    @GetMapping List<DocumentView> list(Authentication authentication) { return documents.ownedDocuments(CurrentUser.require(authentication).id()).stream().map(document -> DocumentView.from(document, documents.currentBuild(document.getId()))).toList(); }
    @PostMapping("/{documentId}/retry") void retry(@PathVariable String documentId, Authentication authentication) { documents.retry(CurrentUser.require(authentication).id(), documentId); }
    @PostMapping("/{documentId}/rebuild") KnowledgeDocumentService.IndexBuildView rebuild(@PathVariable String documentId, @RequestParam(defaultValue = "false") boolean force, Authentication authentication) {
        return documents.rebuild(CurrentUser.require(authentication).id(), documentId, force);
    }
    @GetMapping("/{documentId}/index-versions") List<KnowledgeDocumentService.IndexBuildView> versions(@PathVariable String documentId, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        return documents.ownedIndexVersions(ownerId, documentId).stream().map(documents::indexBuildView).toList();
    }
    public record DocumentView(String id, String transcriptionTaskId, String title, String status, String failureMessage, Instant updatedAt, KnowledgeDocumentService.IndexBuildView currentBuild) {
        public static DocumentView from(KnowledgeDocument document, KnowledgeDocumentService.IndexBuildView currentBuild) { return new DocumentView(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), document.getStatus().name(), document.getFailureMessage(), document.getUpdatedAt(), currentBuild); }
    }
}
