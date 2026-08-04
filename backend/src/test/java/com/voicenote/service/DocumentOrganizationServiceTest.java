package com.voicenote.service;

import com.voicenote.domain.TranscriptSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentOrganizationServiceTest {
    @Test
    void cleansTextMergesAdjacentSpeakerTurnsAndKeepsEverySourceSegment() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "甲", 0, 1_000, "  第一   句 ");
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "甲", 1_200, 2_000, "第二句");
        TranscriptSegment third = new TranscriptSegment("task", 1, 2, "乙", 35_000, 36_000, "第三句");

        var result = DocumentOrganizationService.organize(List.of(first, second, third));

        assertThat(result.turns()).hasSize(2);
        assertThat(result.turns().get(0).text()).isEqualTo("第一 句 第二句");
        assertThat(result.turns().get(0).segmentIds()).containsExactly(first.getId(), second.getId());
        assertThat(result.topics()).hasSize(2);
        assertThat(result.topics().stream().flatMap(topic -> topic.segmentIds().stream()))
                .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
    }
}
