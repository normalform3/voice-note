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
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT") private String textContent;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected TranscriptSegment() { }
    public TranscriptSegment(String taskId, int version, int index, String speaker, long startMs, long endMs, String text) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = taskId; this.transcriptVersion = version; this.segmentIndex = index;
        this.speakerLabel = speaker; this.startMs = startMs; this.endMs = endMs; this.textContent = text; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public int getSegmentIndex() { return segmentIndex; }
    public String getSpeakerLabel() { return speakerLabel; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getTextContent() { return textContent; }
}
