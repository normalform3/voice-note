package com.echotrace.service;

import com.echotrace.domain.TranscriptSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisChunkingTest {
    @Test
    void retainsEvidenceIdsWhenSplittingLongTranscript() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, null, 0, 1_000, "A".repeat(7_900));
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, null, 1_000, 2_000, "B".repeat(1_000));
        List<String> chunks = AnalysisService.chunk(List.of(first, second));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains(first.getId());
        assertThat(chunks.get(1)).contains(second.getId());
    }
}
