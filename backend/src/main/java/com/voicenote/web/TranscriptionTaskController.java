package com.voicenote.web;

import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.TranscriptionTaskService;
import com.voicenote.service.PipelineProgressService;
import com.voicenote.domain.PipelineStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/transcription-tasks")
public class TranscriptionTaskController {
    private final TranscriptionTaskService taskService; private final TranscriptSegmentRepository segments; private final PipelineProgressService progress; private final com.voicenote.service.RecordingDeletionService deletion;
    public TranscriptionTaskController(TranscriptionTaskService taskService, TranscriptSegmentRepository segments, PipelineProgressService progress, com.voicenote.service.RecordingDeletionService deletion) { this.taskService = taskService; this.segments = segments; this.progress = progress; this.deletion = deletion; }
    @PostMapping PipelineProgressService.TaskProgressView create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateTaskRequest request, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication); TranscriptionTask task = taskService.create(user.id(), key, new TranscriptionTaskService.CreateTaskCommand(request.audioBlobId(), request.asrConfig()));
        return progress.ownedView(user.id(), task.getId());
    }
    @PostMapping("/{taskId}/retry") PipelineProgressService.TaskProgressView retry(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication); taskService.retry(user.id(), key, taskId); return progress.ownedView(user.id(), taskId);
    }
    @PostMapping("/{taskId}/stages/{stage}/retry") PipelineProgressService.TaskProgressView retryStage(@PathVariable String taskId, @PathVariable PipelineStage stage, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return taskService.retryStage(CurrentUser.require(authentication).id(), key, taskId, stage);
    }
    @PostMapping("/{taskId}/cancel") PipelineProgressService.TaskProgressView cancel(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return taskService.cancel(CurrentUser.require(authentication).id(), key, taskId);
    }
    @DeleteMapping("/{taskId}") ResponseEntity<Void> delete(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        deletion.delete(CurrentUser.require(authentication).id(), key, taskId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping List<PipelineProgressService.TaskProgressView> list(Authentication authentication) { return progress.ownedViews(CurrentUser.require(authentication).id()); }
    @GetMapping("/{taskId}") PipelineProgressService.TaskProgressView get(@PathVariable String taskId, Authentication authentication) { return progress.ownedView(CurrentUser.require(authentication).id(), taskId); }
    @GetMapping("/{taskId}/segments") List<SegmentView> transcript(@PathVariable String taskId, Authentication authentication) {
        TranscriptionTask task = taskService.ownedTask(CurrentUser.require(authentication).id(), taskId);
        return segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion()).stream().map(SegmentView::from).toList();
    }
    public record CreateTaskRequest(@NotBlank String audioBlobId, TranscriptionTaskService.AsrConfig asrConfig) { }
    public record SegmentView(String id, int index, String speaker, long startMs, long endMs, String text) { static SegmentView from(TranscriptSegment value) { return new SegmentView(value.getId(), value.getSegmentIndex(), value.getSpeakerLabel(), value.getStartMs(), value.getEndMs(), value.getTextContent()); } }
}
