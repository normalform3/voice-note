package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.OrganizedDocument;
import com.voicenote.domain.SceneType;
import com.voicenote.domain.TranscriptSegment;
import com.voicenote.repository.OrganizedDocumentBlockRepository;
import com.voicenote.repository.OrganizedDocumentRepository;
import com.voicenote.repository.OrganizationInvocationRepository;
import com.voicenote.repository.TranscriptSegmentRepository;
import com.voicenote.repository.TranscriptionTaskRepository;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DocumentOrganizationServiceTest {
    @Test
    void cleansTextMergesAdjacentSpeakerTurnsAndKeepsEverySourceSegment() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "甲", 0, 1_000, "  第一   句 ");
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "甲", 20_000, 21_000, "第二句");
        TranscriptSegment third = new TranscriptSegment("task", 1, 2, "乙", 70_000, 71_000, "第三句");

        var result = DocumentOrganizationService.organize(List.of(first, second, third));

        assertThat(result.turns()).hasSize(2);
        assertThat(result.turns().get(0).text()).isEqualTo("第一句。第二句。");
        assertThat(result.turns().get(0).key()).isEqualTo("T000001");
        assertThat(result.turns().get(0).segmentIds()).containsExactly(first.getId(), second.getId());
        assertThat(result.turns().get(0).sourceFragments())
                .extracting(DocumentOrganizationService.SourceFragment::text)
                .containsExactly("  第一   句 ", "第二句");
        assertThat(result.topics()).hasSize(2);
        assertThat(result.topics().stream().flatMap(topic -> topic.segmentIds().stream()))
                .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
        assertThat(result.plainText()).doesNotContain("摘要");
    }

    @Test
    void groupsTurnsUsingTheHumanCorrectedSpeaker() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "第一句");
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "SPEAKER_0", 1_100, 2_000, "第二句");
        second.correctSpeaker("SPEAKER_1");

        var result = DocumentOrganizationService.organize(List.of(first, second));

        assertThat(result.turns()).extracting(DocumentOrganizationService.Turn::speaker)
                .containsExactly("SPEAKER_0", "SPEAKER_1");
    }

    @Test
    void organizesCompleteInterviewQaAndFallsBackOnlyTheUnsafePolishedTurn() {
        TranscriptSegment question = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "请介绍你负责的项目");
        TranscriptSegment answer = new TranscriptSegment("task", 1, 1, "SPEAKER_1", 1_100, 3_000,
                "我负责订单创建、库存扣减，并使用 RocketMQ 异步处理 2 个流程");
        DocumentOrganizationService service = service();
        DocumentOrganizationService.OrganizationWork work = work(List.of(question, answer));
        String response = """
                {"title":"Java 后端工程师面试","topics":[{"title":"项目介绍","items":[{"type":"QA_PAIR","turns":[
                  {"turnKey":"T000001","function":"QUESTION","text":"请介绍你负责的项目。"},
                  {"turnKey":"T000002","function":"ANSWER","text":"我负责订单系统。"}
                ]}]}],"roleSuggestions":[]}
                """;

        var result = service.organizeSemantic(work, response);

        assertThat(result.schemaVersion()).isEqualTo("formal-document-v2");
        assertThat(result.title()).isEqualTo("Java 后端工程师面试");
        assertThat(result.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.title()).isEqualTo("项目介绍");
            assertThat(topic.items()).singleElement().satisfies(item -> {
                assertThat(item.segmentIds()).containsExactly(question.getId(), answer.getId());
                assertThat(item.text()).contains("面试官：请介绍你负责的项目。")
                        .contains("候选人：我负责订单创建、库存扣减，并使用 RocketMQ 异步处理 2 个流程。");
                assertThat(item.turns()).extracting(DocumentOrganizationService.OrganizedTurn::polishAccepted)
                        .containsExactly(true, false);
            });
        });
        assertThat(result.plainText()).doesNotContain("文档摘要", "Topic 摘要");
    }

    @Test
    void rejectsUnknownDuplicateMissingAndOutOfOrderTurnKeys() {
        TranscriptSegment first = new TranscriptSegment("task", 1, 0, "SPEAKER_0", 0, 1_000, "问题");
        TranscriptSegment second = new TranscriptSegment("task", 1, 1, "SPEAKER_1", 1_100, 2_000, "回答");
        DocumentOrganizationService service = service();
        DocumentOrganizationService.OrganizationWork work = work(List.of(first, second));

        assertThatThrownBy(() -> service.organizeSemantic(work, responseWithKeys("T999999", "T000002")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.organizeSemantic(work, responseWithKeys("T000001", "T000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.organizeSemantic(work, responseWithKeys("T000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.organizeSemantic(work, responseWithKeys("T000002", "T000001")))
                .isInstanceOf(IllegalArgumentException.class);

        TranscriptSegment third = new TranscriptSegment("task", 1, 2, "SPEAKER_1", 2_100, 3_000, "补充回答");
        DocumentOrganizationService.OrganizationWork qaWork = work(List.of(first, second, third));
        String bundledQuestions = """
                {"title":"文档","topics":[{"title":"主题","items":[{"type":"QA_PAIR","turns":[
                  {"turnKey":"T000001","function":"QUESTION","text":"问题。"},
                  {"turnKey":"T000002","function":"QUESTION","text":"回答。"},
                  {"turnKey":"T000003","function":"ANSWER","text":"补充回答。"}
                ]}]}]}
                """;
        assertThatThrownBy(() -> service.organizeSemantic(qaWork, bundledQuestions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DocumentOrganizationService service() {
        return new DocumentOrganizationService(mock(OrganizedDocumentRepository.class), mock(OrganizedDocumentBlockRepository.class),
                mock(TranscriptSegmentRepository.class), mock(TranscriptionTaskRepository.class), mock(OrganizationInvocationRepository.class),
                mock(TranscriptSpeakerService.class), mock(OutboxService.class), new ObjectMapper());
    }

    private static DocumentOrganizationService.OrganizationWork work(List<TranscriptSegment> segments) {
        return new DocumentOrganizationService.OrganizationWork(new OrganizedDocument("owner", "task", 1, "interview"), segments,
                Map.of("SPEAKER_0", "面试官", "SPEAKER_1", "候选人"), SceneType.INTERVIEW, "Java 后端工程师");
    }

    private static String responseWithKeys(String... keys) {
        String turns = java.util.Arrays.stream(keys).map(key -> "{\"turnKey\":\"" + key + "\",\"function\":\"STATEMENT\",\"text\":\"内容\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"title\":\"文档\",\"topics\":[{\"title\":\"主题\",\"items\":[{\"type\":\"NARRATIVE\",\"turns\":[" + turns + "]}]}]}";
    }
}
