package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "speaker_correction_suggestions", uniqueConstraints = @UniqueConstraint(name = "uk_speaker_correction_suggestion_index", columnNames = {"run_id", "suggestion_index"}))
public class SpeakerCorrectionSuggestion {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "run_id", nullable = false, columnDefinition = "CHAR(36)") private String runId;
    @Column(name = "suggestion_index", nullable = false) private int suggestionIndex;
    @Column(name = "source_segment_id", nullable = false, columnDefinition = "CHAR(36)") private String sourceSegmentId;
    @Enumerated(EnumType.STRING) @Column(name = "suggestion_type", nullable = false) private SpeakerCorrectionSuggestionType suggestionType;
    @Column(name = "original_speaker_id", nullable = false, length = 128) private String originalSpeakerId;
    @Column(name = "original_start_ms", nullable = false) private long originalStartMs;
    @Column(name = "original_end_ms", nullable = false) private long originalEndMs;
    @Column(name = "original_text", nullable = false, columnDefinition = "TEXT") private String originalText;
    @Column(name = "target_speaker_id", length = 128) private String targetSpeakerId;
    @Column(name = "proposal_document", nullable = false, columnDefinition = "json") private String proposalDocument;
    @Column(nullable = false) private double confidence;
    @Column(nullable = false, length = 512) private String reason;
    @Column(name = "default_selected", nullable = false) private boolean defaultSelected;
    @Enumerated(EnumType.STRING) @Column(name = "timing_source", nullable = false) private SegmentTimingSource timingSource;
    @Column(nullable = false) private boolean applied;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "applied_at") private Instant appliedAt;

    protected SpeakerCorrectionSuggestion() { }
    public SpeakerCorrectionSuggestion(String runId, int index, TranscriptSegment source, SpeakerCorrectionSuggestionType type,
                                       String targetSpeakerId, String proposalDocument, double confidence, String reason,
                                       SegmentTimingSource timingSource) {
        this.id = UUID.randomUUID().toString(); this.runId = runId; this.suggestionIndex = index; this.sourceSegmentId = source.getId();
        this.suggestionType = type; this.originalSpeakerId = source.getEffectiveSpeakerId(); this.originalStartMs = source.getStartMs();
        this.originalEndMs = source.getEndMs(); this.originalText = source.getTextContent(); this.targetSpeakerId = targetSpeakerId;
        this.proposalDocument = proposalDocument; this.confidence = confidence; this.reason = reason;
        this.defaultSelected = confidence >= 0.8d; this.timingSource = timingSource; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getRunId() { return runId; }
    public int getSuggestionIndex() { return suggestionIndex; }
    public String getSourceSegmentId() { return sourceSegmentId; }
    public SpeakerCorrectionSuggestionType getSuggestionType() { return suggestionType; }
    public String getOriginalSpeakerId() { return originalSpeakerId; }
    public long getOriginalStartMs() { return originalStartMs; }
    public long getOriginalEndMs() { return originalEndMs; }
    public String getOriginalText() { return originalText; }
    public String getTargetSpeakerId() { return targetSpeakerId; }
    public String getProposalDocument() { return proposalDocument; }
    public double getConfidence() { return confidence; }
    public String getReason() { return reason; }
    public boolean isDefaultSelected() { return defaultSelected; }
    public SegmentTimingSource getTimingSource() { return timingSource; }
    public boolean isApplied() { return applied; }
    public void markApplied() { applied = true; appliedAt = Instant.now(); }
}
