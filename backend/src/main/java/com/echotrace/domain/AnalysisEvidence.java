package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis_evidence", uniqueConstraints = @UniqueConstraint(name = "uk_evidence_path_segment", columnNames = {"analysis_run_id", "result_path", "transcript_segment_id"}))
public class AnalysisEvidence {
    @Id private String id;
    @Column(name = "analysis_run_id", nullable = false) private String analysisRunId;
    @Column(name = "result_path", nullable = false) private String resultPath;
    @Column(name = "transcript_segment_id", nullable = false) private String transcriptSegmentId;
    @Column(name = "start_offset") private Integer startOffset;
    @Column(name = "end_offset") private Integer endOffset;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected AnalysisEvidence() { }
    public AnalysisEvidence(String runId, String resultPath, String segmentId, Integer startOffset, Integer endOffset) {
        this.id = UUID.randomUUID().toString(); this.analysisRunId = runId; this.resultPath = resultPath; this.transcriptSegmentId = segmentId;
        this.startOffset = startOffset; this.endOffset = endOffset; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getAnalysisRunId() { return analysisRunId; }
    public String getResultPath() { return resultPath; }
    public String getTranscriptSegmentId() { return transcriptSegmentId; }
    public Integer getStartOffset() { return startOffset; }
    public Integer getEndOffset() { return endOffset; }
}
