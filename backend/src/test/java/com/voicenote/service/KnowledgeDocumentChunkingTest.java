package com.voicenote.service;

import com.voicenote.domain.TranscriptSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentChunkingTest {
    @Test
    void preservesSegmentIdsAndSourceTimeRangeAcrossChunks() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "甲", 0, 1_000, "A".repeat(350));
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "乙", 1_000, 2_000, "B".repeat(350));
        List<KnowledgeDocumentService.ChunkDraft> chunks = KnowledgeDocumentService.chunk(List.of(first, second), 500);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).segmentIds()).containsExactly(first.getId());
        assertThat(chunks.get(1).segmentIds()).containsExactly(second.getId());
        assertThat(chunks.get(1).startMs()).isEqualTo(1_000);
        assertThat(chunks.get(1).endMs()).isEqualTo(2_000);
    }
}
