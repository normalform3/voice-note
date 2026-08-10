package com.voicenote.web;

import com.voicenote.service.PipelineProgressService;
import com.voicenote.service.SpeakerCorrectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/speaker-correction-runs")
public class SpeakerCorrectionRunController {
    private final SpeakerCorrectionService service; private final PipelineProgressService progress;
    public SpeakerCorrectionRunController(SpeakerCorrectionService service, PipelineProgressService progress) { this.service = service; this.progress = progress; }

    @GetMapping("/{runId}") SpeakerCorrectionService.RunDetail detail(@PathVariable String runId, Authentication authentication) {
        return service.detail(CurrentUser.require(authentication).id(), runId);
    }

    @PostMapping("/{runId}/apply") ApplyView apply(@PathVariable String runId, @RequestHeader("Idempotency-Key") String key,
                                                    @Valid @RequestBody ApplyRequest request, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        var result = service.apply(ownerId, key, runId, request.suggestionIds(), request.expectedRevision());
        return new ApplyView(result.relabeledSegmentCount(), result.splitSegmentCount(), result.revision(),
                SpeakerCorrectionService.RunView.from(result.run()), progress.ownedView(ownerId, result.run().getTranscriptionTaskId()));
    }

    public record ApplyRequest(@NotEmpty @Size(max = 1000) List<String> suggestionIds,
                               @NotNull @PositiveOrZero Integer expectedRevision) { }
    public record ApplyView(int relabeledSegmentCount, int splitSegmentCount, int revision,
                            SpeakerCorrectionService.RunView run, PipelineProgressService.TaskProgressView task) { }
}
