package com.echotrace.repository;

import com.echotrace.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, String> {
    List<TranscriptSegment> findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(String taskId, int transcriptVersion);
    void deleteByTranscriptionTaskIdAndTranscriptVersion(String taskId, int transcriptVersion);
}
