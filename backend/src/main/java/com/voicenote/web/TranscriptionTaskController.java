package com.voicenote.web;

import com.voicenote.domain.TranscriptSegment;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.TranscriptionTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/transcription-tasks")
public class TranscriptionTaskController {
    private final TranscriptionTaskService taskService; private final TranscriptionTaskRepository tasks; private final TranscriptSegmentRepository segments;
    public TranscriptionTaskController(TranscriptionTaskService taskService, TranscriptionTaskRepository tasks, TranscriptSegmentRepository segments) { this.taskService = taskService; this.tasks = tasks; this.segments = segments; }
    @PostMapping TranscriptionTaskService.TaskView create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateTaskRequest request, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication); return TranscriptionTaskService.TaskView.from(taskService.create(user.id(), key, new TranscriptionTaskService.CreateTaskCommand(request.audioBlobId(), request.asrConfig())));
    }
    @PostMapping("/{taskId}/retry") TranscriptionTaskService.TaskView retry(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return TranscriptionTaskService.TaskView.from(taskService.retry(CurrentUser.require(authentication).id(), key, taskId));
    }
    @GetMapping List<TranscriptionTaskService.TaskView> list(Authentication authentication) { return tasks.findByOwnerIdOrderByUpdatedAtDesc(CurrentUser.require(authentication).id()).stream().map(TranscriptionTaskService.TaskView::from).toList(); }
    @GetMapping("/{taskId}") TranscriptionTaskService.TaskView get(@PathVariable String taskId, Authentication authentication) { return TranscriptionTaskService.TaskView.from(taskService.ownedTask(CurrentUser.require(authentication).id(), taskId)); }
    @GetMapping("/{taskId}/segments") List<SegmentView> transcript(@PathVariable String taskId, Authentication authentication) {
        TranscriptionTask task = taskService.ownedTask(CurrentUser.require(authentication).id(), taskId);
        return segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion()).stream().map(SegmentView::from).toList();
    }
    public record CreateTaskRequest(@NotBlank String audioBlobId, TranscriptionTaskService.AsrConfig asrConfig) { }
    public record SegmentView(String id, int index, String speaker, long startMs, long endMs, String text) { static SegmentView from(TranscriptSegment value) { return new SegmentView(value.getId(), value.getSegmentIndex(), value.getSpeakerLabel(), value.getStartMs(), value.getEndMs(), value.getTextContent()); } }
}
