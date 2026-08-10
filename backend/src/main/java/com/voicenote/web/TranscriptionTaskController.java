package com.voicenote.web;

import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.TranscriptionTaskService;
import com.voicenote.service.PipelineProgressService;
import com.voicenote.service.TranscriptSpeakerCorrectionService;
import com.voicenote.domain.PipelineStage;
import com.voicenote.domain.SpeakerRole;
import com.voicenote.domain.TranscriptSpeaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import com.voicenote.domain.SceneType;

@RestController
@RequestMapping("/api/transcription-tasks")
public class TranscriptionTaskController {
    private final TranscriptionTaskService taskService; private final TranscriptSegmentRepository segments; private final PipelineProgressService progress; private final com.voicenote.service.RecordingDeletionService deletion;
    private final com.voicenote.service.TranscriptSpeakerService speakers;
    private final TranscriptSpeakerCorrectionService speakerCorrections;
    private final com.voicenote.service.SpeakerCorrectionService aiSpeakerCorrections;
    public TranscriptionTaskController(TranscriptionTaskService taskService, TranscriptSegmentRepository segments, PipelineProgressService progress,
                                       com.voicenote.service.RecordingDeletionService deletion, com.voicenote.service.TranscriptSpeakerService speakers,
                                       TranscriptSpeakerCorrectionService speakerCorrections, com.voicenote.service.SpeakerCorrectionService aiSpeakerCorrections) {
        this.taskService = taskService; this.segments = segments; this.progress = progress; this.deletion = deletion; this.speakers = speakers;
        this.speakerCorrections = speakerCorrections; this.aiSpeakerCorrections = aiSpeakerCorrections;
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
    @PatchMapping("/{taskId}/metadata") PipelineProgressService.TaskProgressView updateMetadata(@PathVariable String taskId,
                                                                                                  @Valid @RequestBody UpdateMetadataRequest request,
                                                                                                  Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        taskService.updateMetadata(ownerId, taskId, request.occurredAt(), request.sceneType(), request.subject(), request.tags());
        return progress.ownedView(ownerId, taskId);
    }
    @GetMapping("/{taskId}/segments") List<SegmentView> transcript(@PathVariable String taskId, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        TranscriptionTask task = taskService.ownedTask(ownerId, taskId);
        Map<String, TranscriptSpeaker> speakerIndex = speakers.index(ownerId, taskId);
        return segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion()).stream()
                .map(value -> SegmentView.from(value, speakerIndex.get(value.getEffectiveSpeakerId()))).toList();
    }
    @PatchMapping("/{taskId}/segments/speakers") SpeakerCorrectionView correctSegmentSpeakers(@PathVariable String taskId,
                                                                                               @Valid @RequestBody CorrectSegmentSpeakersRequest request,
                                                                                               Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        var result = speakerCorrections.correct(ownerId, taskId, request.segmentIds(), request.speakerId(), request.expectedRevision());
        return new SpeakerCorrectionView(result.changedSegmentCount(), result.revision(), progress.ownedView(ownerId, taskId));
    }
    @PostMapping("/{taskId}/speaker-correction-runs") com.voicenote.service.SpeakerCorrectionService.RunDetail createAiSpeakerCorrection(
            @PathVariable String taskId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateAiSpeakerCorrectionRequest request, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        var run = aiSpeakerCorrections.create(ownerId, key, taskId, request.expectedRevision());
        return aiSpeakerCorrections.detail(ownerId, run.getId());
    }
    @GetMapping("/{taskId}/speaker-correction-runs/latest") ResponseEntity<com.voicenote.service.SpeakerCorrectionService.RunDetail> latestAiSpeakerCorrection(
            @PathVariable String taskId, Authentication authentication) {
        var detail = aiSpeakerCorrections.latest(CurrentUser.require(authentication).id(), taskId);
        return detail == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(detail);
    }
    @GetMapping("/{taskId}/speakers") List<SpeakerView> listSpeakers(@PathVariable String taskId, Authentication authentication) {
        return speakers.list(CurrentUser.require(authentication).id(), taskId).stream().map(SpeakerView::from).toList();
    }
    @PutMapping("/{taskId}/speakers/{speakerId}") SpeakerView updateSpeaker(@PathVariable String taskId, @PathVariable String speakerId,
                                                                              @Valid @RequestBody UpdateSpeakerRequest request, Authentication authentication) {
        return SpeakerView.from(speakers.rename(CurrentUser.require(authentication).id(), taskId, speakerId, request.displayName()));
    }
    public record CreateTaskRequest(@NotBlank String audioBlobId, TranscriptionTaskService.AsrConfig asrConfig) { }
    public record SegmentView(String id, int index, String speakerId, String asrSpeakerId, String correctedSpeakerId, boolean speakerCorrected,
                              String correctionSource, String timingSource, String rootSegmentId, String parentSegmentId,
                              String speaker, String role, long startMs, long endMs, String text) {
        static SegmentView from(TranscriptSegment value, TranscriptSpeaker speaker) {
            String name = speaker == null || speaker.getDisplayName() == null ? value.getEffectiveSpeakerId() : speaker.getDisplayName();
            String role = speaker == null ? SpeakerRole.UNKNOWN.name() : speaker.getResolvedRole().name();
            return new SegmentView(value.getId(), value.getSegmentIndex(), value.getEffectiveSpeakerId(), value.getAsrSpeakerId(), value.getCorrectedSpeakerId(),
                    value.isSpeakerCorrected(), value.getCorrectionSource().name(), value.getTimingSource().name(), value.getRootSegmentId(), value.getParentSegmentId(),
                    name, role, value.getStartMs(), value.getEndMs(), value.getTextContent());
        }
    }
    public record CorrectSegmentSpeakersRequest(@NotEmpty @Size(max = 1000) List<@NotBlank String> segmentIds,
                                                @Size(max = 128) String speakerId, @NotNull @PositiveOrZero Integer expectedRevision) { }
    public record SpeakerCorrectionView(int changedSegmentCount, int revision, PipelineProgressService.TaskProgressView task) { }
    public record CreateAiSpeakerCorrectionRequest(@NotNull @PositiveOrZero Integer expectedRevision) { }
    public record UpdateSpeakerRequest(@Size(max = 128) String displayName) { }
    public record UpdateMetadataRequest(@NotNull Instant occurredAt, @NotNull SceneType sceneType, @Size(max = 512) String subject,
                                        @Size(max = 20) List<@Size(max = 50) String> tags) { }
    public record SpeakerView(String speakerId, String suggestedRole, Double suggestedConfidence, String confirmedRole, String resolvedRole, String displayName) {
        static SpeakerView from(TranscriptSpeaker value) {
            return new SpeakerView(value.getAsrSpeakerId(), value.getSuggestedRole().name(), value.getSuggestedConfidence(),
                    value.getConfirmedRole() == null ? null : value.getConfirmedRole().name(), value.getResolvedRole().name(), value.getDisplayName());
        }
    }
}
