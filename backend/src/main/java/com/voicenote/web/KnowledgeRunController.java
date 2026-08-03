package com.voicenote.web;

import com.voicenote.domain.KnowledgeRunEvidence;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.repository.KnowledgeRunEvidenceRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.KnowledgeAgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge-runs")
public class KnowledgeRunController {
    private final KnowledgeAgentService runs;
    private final KnowledgeRunEvidenceRepository evidence;
    private final KnowledgeDocumentRepository documents;
    public KnowledgeRunController(KnowledgeAgentService runs, KnowledgeRunEvidenceRepository evidence, KnowledgeDocumentRepository documents) { this.runs = runs; this.evidence = evidence; this.documents = documents; }
    @PostMapping KnowledgeAgentService.KnowledgeRunView create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateKnowledgeRunRequest request, Authentication authentication) {
        return KnowledgeAgentService.KnowledgeRunView.from(runs.create(CurrentUser.require(authentication).id(), key, request.question()));
    }
    @GetMapping List<KnowledgeAgentService.KnowledgeRunView> list(Authentication authentication) { return runs.ownedRuns(CurrentUser.require(authentication).id()).stream().map(KnowledgeAgentService.KnowledgeRunView::from).toList(); }
    @GetMapping("/{runId}") KnowledgeRunDetail get(@PathVariable String runId, Authentication authentication) {
        var run = runs.ownedRun(CurrentUser.require(authentication).id(), runId);
        return new KnowledgeRunDetail(KnowledgeAgentService.KnowledgeRunView.from(run), evidence.findByKnowledgeRunId(runId).stream().map(this::view).toList());
    }
    private EvidenceView view(KnowledgeRunEvidence value) {
        String taskId = documents.findById(value.getKnowledgeDocumentId()).map(document -> document.getTranscriptionTaskId()).orElse(null);
        return new EvidenceView(value.getResultPath(), value.getKnowledgeDocumentId(), value.getKnowledgeChunkId(), taskId, value.getTranscriptSegmentId());
    }
    public record CreateKnowledgeRunRequest(@NotBlank String question) { }
    public record KnowledgeRunDetail(KnowledgeAgentService.KnowledgeRunView run, List<EvidenceView> evidence) { }
    public record EvidenceView(String resultPath, String documentId, String chunkId, String transcriptionTaskId, String segmentId) { }
}
