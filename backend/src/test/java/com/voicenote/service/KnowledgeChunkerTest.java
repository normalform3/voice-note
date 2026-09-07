package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.ChunkProfile;
import com.voicenote.domain.KnowledgeChunkKind;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.domain.SceneType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {
    private final KnowledgeChunker chunker = new KnowledgeChunker(new ObjectMapper(), new KnowledgeTokenEstimator());

    @Test
    void selectsSceneProfilesAndKeepsShortDocumentsWhole() {
        OrganizedDocumentBlock shortBlock = block(0, "topic-short", "short", "SPEAKER_0", "简短备忘", 0, 1_000);
        assertThat(chunker.build(context(SceneType.MEETING), List.of(shortBlock))).singleElement()
                .extracting(KnowledgeChunker.EmbeddedChunk::profile).isEqualTo(ChunkProfile.SHORT_DOCUMENT);

        OrganizedDocumentBlock longSingleSpeaker = block(0, "topic", "one", "SPEAKER_0", chinese(700), 0, 1_000);
        assertThat(profile(SceneType.INTERVIEW, List.of(longSingleSpeaker))).isEqualTo(ChunkProfile.INTERVIEW_QA);
        assertThat(profile(SceneType.MEETING, List.of(longSingleSpeaker))).isEqualTo(ChunkProfile.MEETING_DISCUSSION);
        assertThat(profile(SceneType.OTHER, List.of(longSingleSpeaker))).isEqualTo(ChunkProfile.MONOLOGUE);

        OrganizedDocumentBlock first = block(0, "topic", "first", "SPEAKER_0", chinese(350), 0, 1_000);
        OrganizedDocumentBlock second = block(1, "topic", "second", "SPEAKER_1", chinese(350), 1_000, 2_000);
        assertThat(profile(SceneType.OTHER, List.of(first, second))).isEqualTo(ChunkProfile.CONVERSATION);
    }

    @Test
    void neverMergesDifferentTopics() {
        OrganizedDocumentBlock firstTopic = topic(0, "主题一", 0, 1_000);
        OrganizedDocumentBlock first = child(1, firstTopic, "first", "SPEAKER_0", chinese(340), 0, 1_000, OrganizedBlockType.NARRATIVE);
        OrganizedDocumentBlock secondTopic = topic(2, "主题二", 1_000, 2_000);
        OrganizedDocumentBlock second = child(3, secondTopic, "second", "SPEAKER_0", chinese(340), 1_000, 2_000, OrganizedBlockType.NARRATIVE);

        List<KnowledgeChunker.EmbeddedChunk> chunks = chunker.build(context(SceneType.MEETING), List.of(firstTopic, first, secondTopic, second));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).topics()).extracting(KnowledgeChunker.TopicReference::title).containsExactly("主题一");
        assertThat(chunks.get(1).topics()).extracting(KnowledgeChunker.TopicReference::title).containsExactly("主题二");
    }

    @Test
    void neverMergesDifferentInterviewQuestions() {
        OrganizedDocumentBlock parent = topic(0, "项目经历", 0, 4_000);
        OrganizedDocumentBlock first = qa(1, parent, "q1", "a1", "问题一", chinese(330), 0);
        OrganizedDocumentBlock second = qa(2, parent, "q2", "a2", "问题二", chinese(330), 2_000);

        List<KnowledgeChunker.EmbeddedChunk> chunks = chunker.build(context(SceneType.INTERVIEW), List.of(parent, first, second));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(KnowledgeChunker.EmbeddedChunk::kind).containsOnly(KnowledgeChunkKind.QA);
        assertThat(chunks.get(0).segmentIds()).containsExactly("q1", "a1");
        assertThat(chunks.get(1).segmentIds()).containsExactly("q2", "a2");
    }

    @Test
    void splitsLongAnswersAndRepeatsQuestionAsExplicitContext() {
        OrganizedDocumentBlock parent = topic(0, "系统设计", 0, 5_000);
        String firstAnswer = chinese(700) + "。";
        String secondAnswer = chinese(700) + "。";
        String fragments = "[{\"segmentId\":\"question\",\"speakerId\":\"INTERVIEWER\",\"startMs\":0,\"endMs\":1000,\"text\":\"如何保证可靠性？\"},"
                + "{\"segmentId\":\"answer-1\",\"speakerId\":\"CANDIDATE\",\"startMs\":1000,\"endMs\":3000,\"text\":\"" + firstAnswer + "\"},"
                + "{\"segmentId\":\"answer-2\",\"speakerId\":\"CANDIDATE\",\"startMs\":3000,\"endMs\":5000,\"text\":\"" + secondAnswer + "\"}]";
        OrganizedDocumentBlock qa = new OrganizedDocumentBlock("document", 1, OrganizedBlockType.QA_PAIR, parent.getId(), "系统设计", null,
                "[\"INTERVIEWER\",\"CANDIDATE\"]", 0, 5_000, "[\"question\",\"answer-1\",\"answer-2\"]", fragments,
                "面试官：如何保证可靠性？\n候选人：" + firstAnswer + "\n候选人：" + secondAnswer);

        List<KnowledgeChunker.EmbeddedChunk> chunks = chunker.build(context(SceneType.INTERVIEW), List.of(parent, qa));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.kind()).isEqualTo(KnowledgeChunkKind.ANSWER_PART);
            assertThat(chunk.contextSegmentIds()).containsExactly("question");
            assertThat(chunk.segmentIds()).contains("question");
            assertThat(chunk.content()).contains("如何保证可靠性");
            assertThat(chunk.startMs()).isGreaterThanOrEqualTo(1_000);
            assertThat(chunk.oversized()).isFalse();
        });
    }

    @Test
    void recursivelySplitsLongSingleSegmentWithoutInventingTimestamps() {
        String text = (chinese(500) + "。").repeat(4);
        OrganizedDocumentBlock block = block(0, "topic", "single", "SPEAKER_0", text, 10_000, 20_000);

        List<KnowledgeChunker.EmbeddedChunk> chunks = chunker.build(context(SceneType.OTHER), List.of(block));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.segmentIds()).containsExactly("single");
            assertThat(chunk.startMs()).isEqualTo(10_000);
            assertThat(chunk.endMs()).isEqualTo(20_000);
            assertThat(chunk.oversized()).isFalse();
        });
    }

    @Test
    void overlapsOnlyAnUnsplittableSingleSegment() {
        OrganizedDocumentBlock block = block(0, "topic", "single", "SPEAKER_0", chinese(2_500), 10_000, 20_000);

        List<KnowledgeChunker.EmbeddedChunk> chunks = chunker.build(context(SceneType.OTHER), List.of(block));

        assertThat(chunks).hasSizeGreaterThan(2);
        String first = chunks.get(0).sourceFragments().get(0).text();
        String second = chunks.get(1).sourceFragments().get(0).text();
        assertThat(first.substring(first.length() - 60)).isEqualTo(second.substring(0, 60));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.startMs()).isEqualTo(10_000);
            assertThat(chunk.endMs()).isEqualTo(20_000);
        });
    }

    @Test
    void keepsRawAsrVocabularyInLexicalTextOnly() {
        String fragments = "[{\"segmentId\":\"raw\",\"speakerId\":\"SPEAKER_0\",\"startMs\":0,\"endMs\":1000,\"text\":\"内部代号 Zephyr-X9\"}]";
        OrganizedDocumentBlock block = new OrganizedDocumentBlock("document", 0, OrganizedBlockType.NARRATIVE, "topic", "缓存方案", null,
                "[\"SPEAKER_0\"]", 0, 1_000, "[\"raw\"]", fragments, "采用分层缓存方案。");

        KnowledgeChunker.EmbeddedChunk chunk = chunker.build(context(SceneType.OTHER), List.of(block)).get(0);

        assertThat(chunk.content()).doesNotContain("Zephyr-X9");
        assertThat(chunk.denseText()).doesNotContain("Zephyr-X9");
        assertThat(chunk.lexicalText()).contains("Zephyr-X9");
    }

    private ChunkProfile profile(SceneType scene, List<OrganizedDocumentBlock> blocks) {
        return chunker.resolveProfile(context(scene), chunker.snapshotTopics(blocks));
    }
    private static KnowledgeChunker.ChunkingContext context(SceneType scene) {
        return new KnowledgeChunker.ChunkingContext("录音", scene, "项目复盘", Instant.parse("2026-09-07T00:00:00Z"));
    }
    private static OrganizedDocumentBlock block(int index, String topicId, String segmentId, String speaker, String text, long start, long end) {
        String fragments = "[{\"segmentId\":\"" + segmentId + "\",\"speakerId\":\"" + speaker + "\",\"startMs\":" + start + ",\"endMs\":" + end + ",\"text\":\"" + text + "\"}]";
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.NARRATIVE, topicId, "主题", null, "[\"" + speaker + "\"]", start, end,
                "[\"" + segmentId + "\"]", fragments, speaker + "：" + text);
    }
    private static OrganizedDocumentBlock topic(int index, String title, long start, long end) {
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.TOPIC, null, title, null, "[]", start, end, "[]", "[]", title);
    }
    private static OrganizedDocumentBlock child(int index, OrganizedDocumentBlock parent, String segmentId, String speaker, String text,
                                                  long start, long end, OrganizedBlockType type) {
        String fragments = "[{\"segmentId\":\"" + segmentId + "\",\"speakerId\":\"" + speaker + "\",\"startMs\":" + start + ",\"endMs\":" + end + ",\"text\":\"" + text + "\"}]";
        return new OrganizedDocumentBlock("document", index, type, parent.getId(), parent.getTopicTitle(), null, "[\"" + speaker + "\"]", start, end,
                "[\"" + segmentId + "\"]", fragments, speaker + "：" + text);
    }
    private static OrganizedDocumentBlock qa(int index, OrganizedDocumentBlock parent, String questionId, String answerId,
                                               String question, String answer, long start) {
        String fragments = "[{\"segmentId\":\"" + questionId + "\",\"speakerId\":\"INTERVIEWER\",\"startMs\":" + start + ",\"endMs\":" + (start + 1_000) + ",\"text\":\"" + question + "\"},"
                + "{\"segmentId\":\"" + answerId + "\",\"speakerId\":\"CANDIDATE\",\"startMs\":" + (start + 1_000) + ",\"endMs\":" + (start + 2_000) + ",\"text\":\"" + answer + "\"}]";
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.QA_PAIR, parent.getId(), parent.getTopicTitle(), null,
                "[\"INTERVIEWER\",\"CANDIDATE\"]", start, start + 2_000, "[\"" + questionId + "\",\"" + answerId + "\"]", fragments,
                "面试官：" + question + "\n候选人：" + answer);
    }
    private static String chinese(int length) { return "架".repeat(length); }
}
