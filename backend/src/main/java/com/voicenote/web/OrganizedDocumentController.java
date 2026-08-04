package com.voicenote.web;

import com.voicenote.domain.OrganizedDocument;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.DocumentOrganizationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/organized-documents")
public class OrganizedDocumentController {
    private final DocumentOrganizationService documents;
    private final AnalysisService analyses;

    public OrganizedDocumentController(DocumentOrganizationService documents, AnalysisService analyses) {
        this.documents = documents; this.analyses = analyses;
    }

    @GetMapping("/{documentId}")
    DocumentDetail get(@PathVariable String documentId, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        return new DocumentDetail(DocumentView.from(documents.ownedDocument(user.id(), documentId)),
                documents.ownedBlocks(user.id(), documentId).stream().map(BlockView::from).toList());
    }

    @PostMapping("/{documentId}/summary")
    ResponseEntity<AnalysisService.AnalysisView> summary(@PathVariable String documentId, @RequestHeader("Idempotency-Key") String key,
                                                          Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        OrganizedDocument document = documents.ownedDocument(user.id(), documentId);
        if (document.getStatus() != com.voicenote.domain.OrganizedDocumentStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZED_DOCUMENT_NOT_READY", "Summary requires a completed organized document");
        }
        var run = analyses.createSummary(user.id(), key, document.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisService.AnalysisView.from(run));
    }

    public record DocumentDetail(DocumentView document, List<BlockView> blocks) { }
    public record DocumentView(String id, String taskId, String title, String summary, String organizationMode, String status, String structureDocument, String plainText, String failureMessage) {
        static DocumentView from(OrganizedDocument value) { return new DocumentView(value.getId(), value.getTranscriptionTaskId(), value.getTitle(), value.getSummaryText(), value.getOrganizationMode(), value.getStatus().name(), value.getStructureDocument(), value.getPlainText(), value.getFailureMessage()); }
    }
    public record BlockView(String id, int index, String type, String parentBlockId, String speaker, String speakerIds, String topic, String summary, long startMs, long endMs, String sourceSegmentIds, String sourceFragments, String text) {
        static BlockView from(OrganizedDocumentBlock value) { return new BlockView(value.getId(), value.getBlockIndex(), value.getBlockType().name(), value.getParentBlockId(), value.getSpeakerLabel(), value.getSpeakerIds(), value.getTopicTitle(), value.getSummaryText(), value.getStartMs(), value.getEndMs(), value.getSourceSegmentIds(), value.getSourceFragments(), value.getTextContent()); }
    }
}
