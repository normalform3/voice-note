package com.voicenote.repository;

import com.voicenote.domain.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, String> {
    @Query("select s from TranscriptSegment s where s.transcriptionTaskId = :taskId and s.transcriptVersion = :version and s.active = true order by s.segmentIndex, s.sourceStartOffset, s.sourceEndOffset")
    List<TranscriptSegment> findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(@Param("taskId") String taskId, @Param("version") int transcriptVersion);
    void deleteByTranscriptionTaskIdAndTranscriptVersion(String taskId, int transcriptVersion);
    void deleteByTranscriptionTaskId(String taskId);
}
