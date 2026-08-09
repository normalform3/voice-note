package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcript_segments", uniqueConstraints = @UniqueConstraint(name = "uk_segment_version_index", columnNames = {"transcription_task_id", "transcript_version", "segment_index"}))
public class TranscriptSegment {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "segment_index", nullable = false) private int segmentIndex;
    @Column(name = "speaker_label") private String speakerLabel;
    @Column(name = "asr_speaker_id") private String asrSpeakerId;
    @Column(name = "corrected_speaker_id") private String correctedSpeakerId;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT") private String textContent;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected TranscriptSegment() { }
    public TranscriptSegment(String taskId, int version, int index, String speaker, long startMs, long endMs, String text) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = taskId; this.transcriptVersion = version; this.segmentIndex = index;
        this.asrSpeakerId = speaker == null || speaker.isBlank() ? "SPEAKER_UNKNOWN" : speaker;
        this.speakerLabel = this.asrSpeakerId; this.startMs = startMs; this.endMs = endMs; this.textContent = text; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public int getSegmentIndex() { return segmentIndex; }
    /** Legacy readers should observe the user-corrected identity. */
    public String getSpeakerLabel() { return getEffectiveSpeakerId(); }
    public String getAsrSpeakerId() { return asrSpeakerId == null || asrSpeakerId.isBlank() ? speakerLabel : asrSpeakerId; }
    public String getCorrectedSpeakerId() { return correctedSpeakerId; }
    public String getEffectiveSpeakerId() { return correctedSpeakerId == null || correctedSpeakerId.isBlank() ? getAsrSpeakerId() : correctedSpeakerId; }
    public boolean isSpeakerCorrected() { return correctedSpeakerId != null && !correctedSpeakerId.isBlank(); }
    public boolean correctSpeaker(String speakerId) {
        String normalized = speakerId == null || speakerId.isBlank() || speakerId.equals(getAsrSpeakerId()) ? null : speakerId;
        if (java.util.Objects.equals(correctedSpeakerId, normalized)) return false;
        correctedSpeakerId = normalized;
        return true;
    }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getTextContent() { return textContent; }
}
