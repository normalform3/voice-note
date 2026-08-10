package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcript_segments")
public class TranscriptSegment {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "segment_index", nullable = false) private int segmentIndex;
    @Column(name = "speaker_label") private String speakerLabel;
    @Column(name = "asr_speaker_id") private String asrSpeakerId;
    @Column(name = "corrected_speaker_id") private String correctedSpeakerId;
    @Column(name = "root_segment_id", columnDefinition = "CHAR(36)") private String rootSegmentId;
    @Column(name = "parent_segment_id", columnDefinition = "CHAR(36)") private String parentSegmentId;
    @Column(name = "source_start_offset", nullable = false) private int sourceStartOffset;
    @Column(name = "source_end_offset", nullable = false) private int sourceEndOffset;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Enumerated(EnumType.STRING) @Column(name = "speaker_correction_source", nullable = false) private SpeakerCorrectionSource correctionSource;
    @Enumerated(EnumType.STRING) @Column(name = "timing_source", nullable = false) private SegmentTimingSource timingSource;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT") private String textContent;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected TranscriptSegment() { }
    public TranscriptSegment(String taskId, int version, int index, String speaker, long startMs, long endMs, String text) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = taskId; this.transcriptVersion = version; this.segmentIndex = index;
        this.asrSpeakerId = speaker == null || speaker.isBlank() ? "SPEAKER_UNKNOWN" : speaker;
        this.speakerLabel = this.asrSpeakerId; this.startMs = startMs; this.endMs = endMs; this.textContent = text;
        this.rootSegmentId = this.id; this.sourceStartOffset = 0; this.sourceEndOffset = text.length(); this.active = true;
        this.correctionSource = SpeakerCorrectionSource.ASR; this.timingSource = SegmentTimingSource.ASR; this.createdAt = Instant.now();
    }
    public static TranscriptSegment aiFragment(TranscriptSegment source, int relativeStart, int relativeEnd, String speakerId,
                                               long startMs, long endMs, SegmentTimingSource timingSource) {
        if (!source.active || relativeStart < 0 || relativeEnd <= relativeStart || relativeEnd > source.textContent.length()) {
            throw new IllegalArgumentException("Invalid transcript fragment bounds");
        }
        TranscriptSegment fragment = new TranscriptSegment();
        fragment.id = UUID.randomUUID().toString(); fragment.transcriptionTaskId = source.transcriptionTaskId;
        fragment.transcriptVersion = source.transcriptVersion; fragment.segmentIndex = source.segmentIndex;
        fragment.asrSpeakerId = source.getAsrSpeakerId(); fragment.speakerLabel = fragment.asrSpeakerId;
        fragment.correctedSpeakerId = speakerId.equals(fragment.asrSpeakerId) ? null : speakerId;
        fragment.rootSegmentId = source.getRootSegmentId(); fragment.parentSegmentId = source.id;
        fragment.sourceStartOffset = source.sourceStartOffset + relativeStart; fragment.sourceEndOffset = source.sourceStartOffset + relativeEnd;
        fragment.active = true; fragment.correctionSource = SpeakerCorrectionSource.AI; fragment.timingSource = timingSource;
        fragment.startMs = startMs; fragment.endMs = endMs; fragment.textContent = source.textContent.substring(relativeStart, relativeEnd);
        fragment.createdAt = Instant.now();
        return fragment;
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
    public String getRootSegmentId() { return rootSegmentId == null ? id : rootSegmentId; }
    public String getParentSegmentId() { return parentSegmentId; }
    public int getSourceStartOffset() { return sourceStartOffset; }
    public int getSourceEndOffset() { return sourceEndOffset; }
    public boolean isActive() { return active; }
    public SpeakerCorrectionSource getCorrectionSource() { return correctionSource == null ? (isSpeakerCorrected() ? SpeakerCorrectionSource.HUMAN : SpeakerCorrectionSource.ASR) : correctionSource; }
    public SegmentTimingSource getTimingSource() { return timingSource == null ? SegmentTimingSource.ASR : timingSource; }
    public boolean isHumanCorrected() { return getCorrectionSource() == SpeakerCorrectionSource.HUMAN; }
    public boolean correctSpeaker(String speakerId) {
        String normalized = speakerId == null || speakerId.isBlank() || speakerId.equals(getAsrSpeakerId()) ? null : speakerId;
        SpeakerCorrectionSource nextSource = normalized == null ? SpeakerCorrectionSource.ASR : SpeakerCorrectionSource.HUMAN;
        if (java.util.Objects.equals(correctedSpeakerId, normalized) && getCorrectionSource() == nextSource) return false;
        correctedSpeakerId = normalized; correctionSource = nextSource;
        return true;
    }
    public boolean correctSpeakerByAi(String speakerId) {
        if (!active || isHumanCorrected()) return false;
        String normalized = speakerId == null || speakerId.isBlank() || speakerId.equals(getAsrSpeakerId()) ? null : speakerId;
        if (java.util.Objects.equals(correctedSpeakerId, normalized) && getCorrectionSource() == SpeakerCorrectionSource.AI) return false;
        correctedSpeakerId = normalized; correctionSource = SpeakerCorrectionSource.AI; return true;
    }
    public void deactivateForAiSplit() {
        if (!active || isHumanCorrected()) throw new IllegalStateException("A human-corrected segment cannot be split by AI");
        active = false;
    }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getTextContent() { return textContent; }
}
