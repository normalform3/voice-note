package com.voicenote.web;

import com.voicenote.security.UserPrincipal;
import com.voicenote.service.UploadService;
import com.voicenote.service.TranscriptionTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) { this.uploads = uploads; }
    @PostMapping("/intents")
    ResponseEntity<UploadService.UploadIntent> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody UploadIntentRequest request, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        UploadService.UploadIntent intent = uploads.createIntent(user.id(), key, new UploadService.CreateUploadIntent(request.sha256(), request.contentLength(), request.contentType(), request.originalFilename()));
        return ResponseEntity.status(intent.contentReady() ? HttpStatus.OK : HttpStatus.CREATED).body(intent);
    }
    @PutMapping(value = "/intents/{blobId}/content", consumes = "application/octet-stream")
    ResponseEntity<Void> content(@PathVariable String blobId, HttpServletRequest request, Authentication authentication) throws IOException {
        uploads.uploadContent(CurrentUser.require(authentication).id(), blobId, request.getInputStream());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/intents/{blobId}/complete")
    ResponseEntity<com.voicenote.service.PipelineProgressService.TaskProgressView> complete(@PathVariable String blobId,
                                                                                              @RequestHeader("Idempotency-Key") String key,
                                                                                              @RequestBody(required = false) CompleteUploadRequest request,
                                                                                              Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        var view = uploads.complete(user.id(), key, blobId,
                request == null ? null : request.asrConfig());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }
    @GetMapping("/intents/{blobId}") UploadService.UploadIntent get(@PathVariable String blobId, Authentication authentication) { return uploads.byId(CurrentUser.require(authentication).id(), blobId); }
    public record UploadIntentRequest(@NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256, @Min(1) long contentLength, @NotBlank String contentType, @NotBlank String originalFilename) { }
    public record CompleteUploadRequest(TranscriptionTaskService.AsrConfig asrConfig) { }
}
