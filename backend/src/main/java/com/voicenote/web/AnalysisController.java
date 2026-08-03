package com.voicenote.web;

import com.voicenote.domain.AnalysisEvidence;
import com.voicenote.domain.AnalysisRun;
import com.voicenote.repository.AnalysisEvidenceRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.AnalysisService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/analysis-runs")
public class AnalysisController {
    private final AnalysisService analyses; private final AnalysisEvidenceRepository evidence;
    public AnalysisController(AnalysisService analyses, AnalysisEvidenceRepository evidence) { this.analyses = analyses; this.evidence = evidence; }
    @PostMapping AnalysisService.AnalysisView create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateAnalysisRequest request, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication); return AnalysisService.AnalysisView.from(analyses.create(user.id(), key, new AnalysisService.CreateAnalysisCommand(request.transcriptionTaskId(), request.mode(), request.goal())));
    }
    @GetMapping("/{runId}") AnalysisDetail get(@PathVariable String runId, Authentication authentication) {
        AnalysisRun run = analyses.ownedRun(CurrentUser.require(authentication).id(), runId);
        return new AnalysisDetail(AnalysisService.AnalysisView.from(run), evidence.findByAnalysisRunId(runId).stream().map(EvidenceView::from).toList());
    }
    public record CreateAnalysisRequest(@NotBlank String transcriptionTaskId, String mode, @NotBlank String goal) { }
    public record AnalysisDetail(AnalysisService.AnalysisView run, List<EvidenceView> evidence) { }
    public record EvidenceView(String resultPath, String segmentId, Integer startOffset, Integer endOffset) { static EvidenceView from(AnalysisEvidence value) { return new EvidenceView(value.getResultPath(), value.getTranscriptSegmentId(), value.getStartOffset(), value.getEndOffset()); } }
}
