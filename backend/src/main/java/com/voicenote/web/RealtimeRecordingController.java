package com.voicenote.web;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.RealtimeRecordingSession;
import com.voicenote.security.RealtimeRecordingTicketService;
import com.voicenote.service.RealtimeRecordingService;
import com.voicenote.service.TranscriptionTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/realtime-recordings")
public class RealtimeRecordingController {
    private final RealtimeRecordingService recordings;
    private final RealtimeRecordingTicketService tickets;
    private final AppProperties properties;
    public RealtimeRecordingController(RealtimeRecordingService recordings, RealtimeRecordingTicketService tickets, AppProperties properties) {
        this.recordings = recordings; this.tickets = tickets; this.properties = properties;
    }

    @GetMapping("/capabilities")
    Capabilities capabilities() {
        boolean enabled = recordings.isEnabled();
        return new Capabilities(enabled, properties.getRealtimeAsr().getMaxDurationSeconds(), List.of("zh", "en"),
                enabled ? null : "REALTIME_ASR_DISABLED");
    }

    @PostMapping
    ResponseEntity<CreatedView> create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        RealtimeRecordingSession session = recordings.create(ownerId, new RealtimeRecordingService.CreateCommand(request.startedAt(), request.contentType(),
                request.originalFilename(), request.sampleRate(), request.languageHints(), request.asrConfig()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created(session, tickets.issue(ownerId, session.getId())));
    }

    @PutMapping(value = "/{sessionId}/parts/{partNumber}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<Void> uploadPart(@PathVariable String sessionId, @PathVariable int partNumber,
                                    @RequestHeader(HttpHeaders.CONTENT_LENGTH) long contentLength,
                                    @RequestHeader("X-Content-SHA256") String sha256,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    HttpServletRequest request, Authentication authentication) throws IOException {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            throw new com.voicenote.web.ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "录音分片幂等键无效");
        }
        recordings.uploadPart(CurrentUser.require(authentication).id(), sessionId, partNumber, contentLength, sha256, request.getInputStream());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/complete")
    ResponseEntity<SessionView> complete(@PathVariable String sessionId, @Valid @RequestBody CompleteRequest request, Authentication authentication) {
        RealtimeRecordingSession session = recordings.complete(CurrentUser.require(authentication).id(), sessionId, request.partCount());
        return ResponseEntity.status(session.getStatus().name().equals("READY") ? HttpStatus.OK : HttpStatus.ACCEPTED).body(SessionView.from(session));
    }

    // Keep the generic session lookup from shadowing the dedicated
    // /realtime-recordings/socket WebSocket handler.
    @GetMapping("/{sessionId:[0-9a-fA-F-]{36}}")
    SessionView get(@PathVariable String sessionId, Authentication authentication) {
        return SessionView.from(recordings.owned(CurrentUser.require(authentication).id(), sessionId));
    }

    @PostMapping("/{sessionId}/realtime-ticket")
    TicketView ticket(@PathVariable String sessionId, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        recordings.owned(ownerId, sessionId);
        var ticket = tickets.issue(ownerId, sessionId);
        return new TicketView(ticket.value(), ticket.expiresAt(), "/api/realtime-recordings/socket");
    }

    @DeleteMapping("/{sessionId:[0-9a-fA-F-]{36}}")
    ResponseEntity<Void> abort(@PathVariable String sessionId, Authentication authentication) {
        recordings.abort(CurrentUser.require(authentication).id(), sessionId);
        return ResponseEntity.noContent().build();
    }

    private static CreatedView created(RealtimeRecordingSession session, RealtimeRecordingTicketService.Ticket ticket) {
        return new CreatedView(SessionView.from(session), new TicketView(ticket.value(), ticket.expiresAt(), "/api/realtime-recordings/socket"));
    }
    public record Capabilities(boolean enabled, int maxDurationSeconds, List<String> defaultLanguages, String unavailableCode) { }
    public record CreateRequest(@NotNull Instant startedAt, @NotBlank @Size(max = 128) String contentType,
                                @NotBlank @Size(max = 512) String originalFilename, @Min(8000) @Max(96000) int sampleRate,
                                List<String> languageHints, TranscriptionTaskService.AsrConfig asrConfig) { }
    public record CompleteRequest(@Min(1) int partCount) { }
    public record TicketView(String ticket, Instant expiresAt, String websocketPath) { }
    public record CreatedView(SessionView session, TicketView realtime) { }
    public record SessionView(String id, String status, int nextPartNumber, long totalBytes, Instant startedAt,
                              String audioBlobId, String taskId, String failureCode, String failureMessage, Instant expiresAt) {
        static SessionView from(RealtimeRecordingSession value) {
            return new SessionView(value.getId(), value.getStatus().name(), value.getNextPartNumber(), value.getTotalBytes(), value.getStartedAt(),
                    value.getAudioBlobId(), value.getTranscriptionTaskId(), value.getFailureCode(), value.getFailureMessage(), value.getExpiresAt());
        }
    }
}
