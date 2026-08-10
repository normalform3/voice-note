package com.voicenote.service;

import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.domain.TranscriptSegment;
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

    @Test
    void summarizesOnlyAtomicFormalDocumentBlocksWithoutRepeatingTopicAggregates() {
        OrganizedDocumentBlock topic = new OrganizedDocumentBlock("document", 0, OrganizedBlockType.TOPIC, null, "Redis", null,
                "[\"SPEAKER_0\"]", 0, 2_000, "[\"segment-a\"]", "[]", "聚合内容不应重复");
        OrganizedDocumentBlock qa = new OrganizedDocumentBlock("document", 1, OrganizedBlockType.QA_PAIR, topic.getId(), "Redis", null,
                "[\"SPEAKER_0\",\"SPEAKER_1\"]", 0, 2_000, "[\"segment-a\"]", "[]", "面试官：问题\n候选人：回答");

        List<String> chunks = AnalysisService.chunkOrganized(List.of(topic, qa));

        assertThat(chunks).singleElement().asString().contains("面试官：问题", "候选人：回答").doesNotContain("聚合内容不应重复");
    }
}
