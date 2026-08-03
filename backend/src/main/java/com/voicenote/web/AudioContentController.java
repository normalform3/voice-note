package com.voicenote.web;

import com.voicenote.domain.AudioBlob;
import com.voicenote.domain.TranscriptionTask;
import com.voicenote.repository.AudioBlobRepository;
import com.voicenote.security.UserPrincipal;
import com.voicenote.service.ObjectStorage;
import com.voicenote.service.TranscriptionTaskService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audio")
public class AudioContentController {
    private final TranscriptionTaskService tasks; private final AudioBlobRepository blobs; private final ObjectStorage storage;
    public AudioContentController(TranscriptionTaskService tasks, AudioBlobRepository blobs, ObjectStorage storage) { this.tasks = tasks; this.blobs = blobs; this.storage = storage; }
    @GetMapping("/{taskId}/content")
    ResponseEntity<InputStreamResource> content(@PathVariable String taskId, Authentication authentication) {
        UserPrincipal user = CurrentUser.require(authentication);
        TranscriptionTask task = tasks.ownedTask(user.id(), taskId);
        AudioBlob blob = blobs.findById(task.getAudioBlobId()).orElseThrow();
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(blob.getContentType()); } catch (IllegalArgumentException exception) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().contentType(mediaType).contentLength(blob.getContentLength()).body(new InputStreamResource(storage.get(blob.getObjectKey())));
    }
}
