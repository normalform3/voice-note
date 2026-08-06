package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.provider.TextEmbeddingClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {
    @Test
    void preservesRawSegmentsAndSplitsAtSemanticUnitsWhenTheTokenLimitIsExceeded() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setChunkTargetTokens(800);
        properties.getKnowledge().setChunkMaxTokens(1200);
        TextEmbeddingClient embeddings = new TextEmbeddingClient() {
            @Override public List<List<Double>> embedDocuments(List<String> texts) { return texts.stream().map(value -> List.of(1.0)).toList(); }
            @Override public List<Double> embedQuery(String text) { return List.of(1.0); }
            @Override public EmbeddedDocument embedDocumentWithUsage(String text) { return new EmbeddedDocument(List.of(1.0), text.contains("第二段") ? 1300 : 400); }
        };
        OrganizedDocumentBlock first = block(1, "first", "第一段", 0, 1_000);
        OrganizedDocumentBlock second = block(2, "second", "第二段", 1_000, 2_000);

        List<KnowledgeChunker.EmbeddedChunk> chunks = new KnowledgeChunker(new ObjectMapper(), properties, embeddings).build("会议", List.of(first, second));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).segmentIds()).containsExactly("first");
        assertThat(chunks.get(1).segmentIds()).containsExactly("second");
        assertThat(chunks.get(0).speakerIds()).containsExactly("SPEAKER_0");
        assertThat(chunks.get(1).contextSegmentIds()).containsExactly("first");
        assertThat(chunks.get(1).sourceFragments()).extracting(KnowledgeChunker.Fragment::segmentId).containsExactly("first", "second");
    }

    @Test
    void combinesOnlyAdjacentShortTopicsAndKeepsTheirTopicReferences() {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setShortTopicTokens(200);
        properties.getKnowledge().setChunkTargetTokens(800);
        properties.getKnowledge().setChunkMaxTokens(1200);
        TextEmbeddingClient embeddings = embeddings(text -> text.contains("长主题") ? 400 : 100);
        OrganizedDocumentBlock firstTopic = topic(0, "短主题一", 0, 1_000);
        OrganizedDocumentBlock first = child(1, firstTopic, "短主题一", "短一", 0, 1_000);
        OrganizedDocumentBlock secondTopic = topic(2, "短主题二", 1_000, 2_000);
        OrganizedDocumentBlock second = child(3, secondTopic, "短主题二", "短二", 1_000, 2_000);
        OrganizedDocumentBlock longTopic = topic(4, "长主题", 2_000, 3_000);
        OrganizedDocumentBlock third = child(5, longTopic, "长主题", "长内容", 2_000, 3_000);

        List<KnowledgeChunker.EmbeddedChunk> chunks = new KnowledgeChunker(new ObjectMapper(), properties, embeddings).build("会议", List.of(firstTopic, first, secondTopic, second, longTopic, third));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).topics()).extracting(KnowledgeChunker.TopicReference::title).containsExactly("短主题一", "短主题二");
        assertThat(chunks.get(1).topics()).extracting(KnowledgeChunker.TopicReference::title).containsExactly("长主题");
    }

    private static OrganizedDocumentBlock block(int index, String segmentId, String text, long start, long end) {
        String fragments = "[{\"segmentId\":\"" + segmentId + "\",\"speakerId\":\"SPEAKER_0\",\"startMs\":" + start + ",\"endMs\":" + end + ",\"text\":\"" + text + "\"}]";
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.NARRATIVE, "topic", "主题", null, "[\"SPEAKER_0\"]", start, end,
                "[\"" + segmentId + "\"]", fragments, "SPEAKER_0: " + text);
    }

    private static OrganizedDocumentBlock topic(int index, String title, long start, long end) {
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.TOPIC, null, title, null, "[]", start, end, "[]", "[]", title);
    }
    private static OrganizedDocumentBlock child(int index, OrganizedDocumentBlock parent, String topic, String text, long start, long end) {
        String fragments = "[{\"segmentId\":\"segment-" + index + "\",\"speakerId\":\"SPEAKER_0\",\"startMs\":" + start + ",\"endMs\":" + end + ",\"text\":\"" + text + "\"}]";
        return new OrganizedDocumentBlock("document", index, OrganizedBlockType.NARRATIVE, parent.getId(), topic, null, "[\"SPEAKER_0\"]", start, end,
                "[\"segment-" + index + "\"]", fragments, "SPEAKER_0: " + text);
    }
    private static TextEmbeddingClient embeddings(java.util.function.ToIntFunction<String> tokens) {
        return new TextEmbeddingClient() {
            @Override public List<List<Double>> embedDocuments(List<String> texts) { return texts.stream().map(value -> List.of(1.0)).toList(); }
            @Override public List<Double> embedQuery(String text) { return List.of(1.0); }
            @Override public EmbeddedDocument embedDocumentWithUsage(String text) { return new EmbeddedDocument(List.of(1.0), tokens.applyAsInt(text)); }
        };
    }
}
