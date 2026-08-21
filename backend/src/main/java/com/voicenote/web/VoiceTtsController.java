package com.voicenote.web;

import com.voicenote.service.VoiceTtsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/voice")
public class VoiceTtsController {
    private final VoiceTtsService tts;
    public VoiceTtsController(VoiceTtsService tts) { this.tts = tts; }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> speak(@Valid @RequestBody TtsRequest request) {
        if (!tts.isEnabled()) throw new ApiException(HttpStatus.NOT_FOUND, "TTS_DISABLED", "Voice playback is not enabled");
        try { UUID.fromString(request.utteranceId()); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UTTERANCE_ID", "utteranceId must be a UUID"); }
        StreamingResponseBody body = output -> tts.stream(request.utteranceId(), request.text().trim(), output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Audio-Format", "pcm-s16le")
                .header("X-Audio-Sample-Rate", "24000")
                .header("X-Audio-Channels", "1")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    record TtsRequest(@NotBlank String utteranceId, @NotBlank @Size(max = 500) String text) { }
}
