package com.voicenote.web;

import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.TranscriptionTaskService;
import com.voicenote.service.PipelineProgressService;
import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.SpeakerRole;
import com.voicenote.domain.TranscriptSpeaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transcription-tasks")
public class TranscriptionTaskController {
    private final TranscriptionTaskService taskService; private final TranscriptSegmentRepository segments; private final PipelineProgressService progress; private final com.voicenote.service.RecordingDeletionService deletion;
    private final com.voicenote.service.TranscriptSpeakerService speakers;
    public TranscriptionTaskController(TranscriptionTaskService taskService, TranscriptSegmentRepository segments, PipelineProgressService progress,
                                       com.voicenote.service.RecordingDeletionService deletion, com.voicenote.service.TranscriptSpeakerService speakers) {
        this.taskService = taskService; this.segments = segments; this.progress = progress; this.deletion = deletion; this.speakers = speakers;
    }
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
    @PostMapping("/{taskId}/formal-document") PipelineProgressService.TaskProgressView formalDocument(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return taskService.createFormalDocument(CurrentUser.require(authentication).id(), key, taskId);
    }
    @PostMapping("/{taskId}/knowledge-build") PipelineProgressService.TaskProgressView knowledgeBuild(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return taskService.createKnowledgeBuild(CurrentUser.require(authentication).id(), key, taskId);
    }
    @DeleteMapping("/{taskId}") ResponseEntity<Void> delete(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        deletion.delete(CurrentUser.require(authentication).id(), key, taskId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping List<PipelineProgressService.TaskProgressView> list(Authentication authentication) { return progress.ownedViews(CurrentUser.require(authentication).id()); }
    @GetMapping("/{taskId}") PipelineProgressService.TaskProgressView get(@PathVariable String taskId, Authentication authentication) { return progress.ownedView(CurrentUser.require(authentication).id(), taskId); }
    @GetMapping("/{taskId}/segments") List<SegmentView> transcript(@PathVariable String taskId, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        TranscriptionTask task = taskService.ownedTask(ownerId, taskId);
        Map<String, TranscriptSpeaker> speakerIndex = speakers.index(ownerId, taskId);
        return segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion()).stream()
                .map(value -> SegmentView.from(value, speakerIndex.get(value.getAsrSpeakerId()))).toList();
    }
    @GetMapping("/{taskId}/speakers") List<SpeakerView> listSpeakers(@PathVariable String taskId, Authentication authentication) {
        return speakers.list(CurrentUser.require(authentication).id(), taskId).stream().map(SpeakerView::from).toList();
    }
    @PutMapping("/{taskId}/speakers/{speakerId}") SpeakerView updateSpeaker(@PathVariable String taskId, @PathVariable String speakerId,
                                                                              @Valid @RequestBody UpdateSpeakerRequest request, Authentication authentication) {
        return SpeakerView.from(speakers.rename(CurrentUser.require(authentication).id(), taskId, speakerId, request.displayName()));
    }
    public record CreateTaskRequest(@NotBlank String audioBlobId, TranscriptionTaskService.AsrConfig asrConfig) { }
    public record SegmentView(String id, int index, String speakerId, String speaker, String role, long startMs, long endMs, String text) {
        static SegmentView from(TranscriptSegment value, TranscriptSpeaker speaker) {
            String name = speaker == null || speaker.getDisplayName() == null ? value.getAsrSpeakerId() : speaker.getDisplayName();
            String role = speaker == null ? SpeakerRole.UNKNOWN.name() : speaker.getResolvedRole().name();
            return new SegmentView(value.getId(), value.getSegmentIndex(), value.getAsrSpeakerId(), name, role, value.getStartMs(), value.getEndMs(), value.getTextContent());
        }
    }
    public record UpdateSpeakerRequest(@Size(max = 128) String displayName) { }
    public record SpeakerView(String speakerId, String suggestedRole, Double suggestedConfidence, String confirmedRole, String resolvedRole, String displayName) {
        static SpeakerView from(TranscriptSpeaker value) {
            return new SpeakerView(value.getAsrSpeakerId(), value.getSuggestedRole().name(), value.getSuggestedConfidence(),
                    value.getConfirmedRole() == null ? null : value.getConfirmedRole().name(), value.getResolvedRole().name(), value.getDisplayName());
        }
    }
}
