package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcript_speakers", uniqueConstraints = @UniqueConstraint(name = "uk_transcript_speaker", columnNames = {"transcription_task_id", "transcript_version", "asr_speaker_id"}))
public class TranscriptSpeaker {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "asr_speaker_id", nullable = false, length = 128) private String asrSpeakerId;
    @Enumerated(EnumType.STRING) @Column(name = "suggested_role", nullable = false) private SpeakerRole suggestedRole;
    @Column(name = "suggested_confidence") private Double suggestedConfidence;
    @Enumerated(EnumType.STRING) @Column(name = "confirmed_role") private SpeakerRole confirmedRole;
    @Column(name = "display_name", length = 128) private String displayName;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TranscriptSpeaker() { }
    public TranscriptSpeaker(String taskId, int transcriptVersion, String asrSpeakerId) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = taskId; this.transcriptVersion = transcriptVersion;
        this.asrSpeakerId = asrSpeakerId; this.suggestedRole = SpeakerRole.UNKNOWN;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public String getAsrSpeakerId() { return asrSpeakerId; }
    public SpeakerRole getSuggestedRole() { return suggestedRole; }
    public Double getSuggestedConfidence() { return suggestedConfidence; }
    public SpeakerRole getConfirmedRole() { return confirmedRole; }
    public String getDisplayName() { return displayName; }
    public SpeakerRole getResolvedRole() { return confirmedRole == null ? suggestedRole : confirmedRole; }
    public void suggest(SpeakerRole role, Double confidence) {
        if (confirmedRole != null) return;
        suggestedRole = role == null ? SpeakerRole.UNKNOWN : role;
        suggestedConfidence = confidence; updatedAt = Instant.now();
    }
    public void confirm(SpeakerRole role, String name) {
        confirmedRole = role == null ? SpeakerRole.UNKNOWN : role;
        displayName = name == null || name.isBlank() ? null : name.trim(); updatedAt = Instant.now();
    }
}
