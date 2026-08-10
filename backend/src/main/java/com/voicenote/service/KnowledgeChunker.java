package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeTopic;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.provider.ProviderException;
import com.voicenote.provider.TextEmbeddingClient;
import org.springframework.stereotype.Component;
import java.util.*;

/** Builds chunks from persisted topic snapshots. Topic boundaries remain explicit even when short adjacent topics share a chunk. */
@Component
public class KnowledgeChunker {
    private static final int MIN_ACCEPTED_TOKENS = 200;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final TextEmbeddingClient embeddings;

    public KnowledgeChunker(ObjectMapper mapper, AppProperties properties, TextEmbeddingClient embeddings) {
        this.mapper = mapper; this.properties = properties; this.embeddings = embeddings;
    }

    /** Compatibility entrypoint for callers and tests that have not yet persisted topic snapshots. */
    public List<EmbeddedChunk> build(String documentTitle, List<OrganizedDocumentBlock> blocks) {
        return buildTopics(documentTitle, topicsFromBlocks(blocks));
    }

    public List<TopicSnapshot> snapshotTopics(List<OrganizedDocumentBlock> blocks) {
        return List.copyOf(topicsFromBlocks(blocks));
    }

    public List<EmbeddedChunk> buildFromTopics(String documentTitle, List<KnowledgeTopic> topics) {
        List<TopicSnapshot> snapshots = new ArrayList<>();
        for (KnowledgeTopic topic : topics) {
            try {
                List<UnitSnapshot> units = mapper.readValue(topic.getSourceUnitSnapshots(), new TypeReference<>() { });
                snapshots.add(new TopicSnapshot(topic.getId(), topic.getTopicIndex(), topic.getTitle(), units));
            } catch (Exception exception) {
                throw new IllegalStateException("Knowledge topic has invalid unit snapshots", exception);
            }
        }
        return buildTopics(documentTitle, snapshots);
    }

    private List<EmbeddedChunk> buildTopics(String documentTitle, List<TopicSnapshot> snapshots) {
        List<TopicData> topics = snapshots.stream().sorted(Comparator.comparingInt(TopicSnapshot::topicIndex)).map(this::toTopic).filter(value -> !value.units.isEmpty()).toList();
        if (topics.isEmpty()) return List.of();
        int target = Math.max(200, properties.getKnowledge().getChunkTargetTokens());
        int shortLimit = Math.max(1, properties.getKnowledge().getShortTopicTokens());
        List<List<TopicData>> groups = new ArrayList<>();
        topics.forEach(topic -> groups.add(new ArrayList<>(List.of(topic))));
        int groupIndex = 0;
        while (groupIndex < groups.size()) {
            List<TopicData> current = groups.get(groupIndex);
            if (measure(documentTitle, units(current)).tokens > shortLimit) { groupIndex++; continue; }
            boolean merged = false;
            if (groupIndex + 1 < groups.size()) {
                List<TopicData> candidate = new ArrayList<>(current); candidate.addAll(groups.get(groupIndex + 1));
                if (measure(documentTitle, units(candidate)).tokens <= target) {
                    groups.set(groupIndex, candidate); groups.remove(groupIndex + 1); merged = true;
                }
            }
            if (!merged && groupIndex > 0) {
                List<TopicData> candidate = new ArrayList<>(groups.get(groupIndex - 1)); candidate.addAll(current);
                if (measure(documentTitle, units(candidate)).tokens <= target) {
                    groups.set(groupIndex - 1, candidate); groups.remove(groupIndex); groupIndex--; merged = true;
                }
            }
            if (!merged || measure(documentTitle, units(groups.get(groupIndex))).tokens > shortLimit) groupIndex++;
        }
        List<EmbeddedChunk> output = new ArrayList<>();
        for (List<TopicData> group : groups) {
            List<Unit> groupedUnits = units(group);
            if (group.size() > 1) {
                Measured measured = measure(documentTitle, groupedUnits);
                int maximum = Math.max(target, properties.getKnowledge().getChunkMaxTokens());
                output.add(toChunk(documentTitle, groupedUnits, measured.embedded, measured.tokens > maximum));
            } else output.addAll(splitTopic(documentTitle, group.get(0)));
        }
        return List.copyOf(output);
    }

    private static List<Unit> units(List<TopicData> topics) { return topics.stream().flatMap(value -> value.units.stream()).toList(); }

    private List<EmbeddedChunk> splitTopic(String documentTitle, TopicData topic) {
        int target = Math.max(200, properties.getKnowledge().getChunkTargetTokens());
        int maximum = Math.max(target, properties.getKnowledge().getChunkMaxTokens());
        List<EmbeddedChunk> output = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        TextEmbeddingClient.EmbeddedDocument currentEmbedded = null;
        int cursor = 0;
        while (cursor < topic.units.size()) {
            Unit next = topic.units.get(cursor);
            List<Unit> candidate = new ArrayList<>(current); candidate.add(next);
            Measured measured = measure(documentTitle, candidate);
            if (measured.tokens <= maximum && (measured.tokens <= target || current.isEmpty() || requiredTokens(currentEmbedded) < MIN_ACCEPTED_TOKENS)) {
                current = candidate; currentEmbedded = measured.embedded; cursor++; continue;
            }
            if (!current.isEmpty()) {
                output.add(toChunk(documentTitle, current, currentEmbedded, false));
                current = new ArrayList<>(); currentEmbedded = null; continue;
            }
            output.add(toChunk(documentTitle, List.of(next), measured.embedded, true)); cursor++;
        }
        if (!current.isEmpty()) output.add(toChunk(documentTitle, current, currentEmbedded, false));
        return output;
    }

    private Measured measure(String title, List<Unit> units) {
        TextEmbeddingClient.EmbeddedDocument embedded = embeddings.embedDocumentWithUsage(render(title, units));
        return new Measured(embedded, requiredTokens(embedded));
    }

    private static int requiredTokens(TextEmbeddingClient.EmbeddedDocument value) {
        if (value == null || value.promptTokens() == null) {
            throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_USAGE_MISSING", "Embedding provider did not report prompt token usage");
        }
        return value.promptTokens();
    }

    private static String render(String title, List<Unit> units) {
        StringBuilder output = new StringBuilder("# ").append(title).append('\n');
        String topicId = null;
        for (Unit unit : units) {
            if (!Objects.equals(topicId, unit.topicId)) {
                output.append("## ").append(unit.topicTitle).append('\n'); topicId = unit.topicId;
            }
            output.append(unit.text).append('\n');
        }
        return output.toString();
    }

    private static EmbeddedChunk toChunk(String title, List<Unit> units, TextEmbeddingClient.EmbeddedDocument embedded, boolean oversized) {
        LinkedHashMap<String, Fragment> fragments = new LinkedHashMap<>();
        for (Unit unit : units) unit.fragments.forEach(value -> fragments.put(value.segmentId, value));
        List<String> primaryIds = units.stream().flatMap(unit -> unit.segmentIds.stream()).distinct().toList();
        List<String> blockIds = units.stream().map(unit -> unit.blockId).filter(Objects::nonNull).distinct().toList();
        List<String> speakerIds = fragments.values().stream().map(Fragment::speakerId).filter(value -> value != null && !value.isBlank()).distinct().toList();
        List<TopicReference> topics = units.stream().collect(java.util.stream.Collectors.toMap(unit -> unit.topicId,
                unit -> new TopicReference(unit.topicId, unit.topicTitle, unit.topicIndex), (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        String topicTitle = topics.stream().map(TopicReference::title).distinct().reduce((left, right) -> left + " / " + right).orElse("整理片段");
        return new EmbeddedChunk(topics, topicTitle, units.get(0).startMs, units.get(units.size() - 1).endMs, primaryIds, List.of(), blockIds,
                speakerIds, List.copyOf(fragments.values()), render(title, units), requiredTokens(embedded), embedded.vector(), oversized);
    }

    private TopicData toTopic(TopicSnapshot topic) {
        List<Unit> units = new ArrayList<>();
        for (UnitSnapshot source : topic.units()) {
            List<Fragment> fragments = fragments(source.sourceFragments(), source.sourceSegmentIds(), source.speakerLabel(), source.startMs(), source.endMs(), source.text());
            units.add(new Unit(topic.id(), topic.topicIndex(), source.blockId(), topic.title(), source.text(), source.startMs(), source.endMs(), fragments));
        }
        return new TopicData(topic, units);
    }

    private List<TopicSnapshot> topicsFromBlocks(List<OrganizedDocumentBlock> blocks) {
        List<OrganizedDocumentBlock> ordered = blocks.stream().sorted(Comparator.comparingInt(OrganizedDocumentBlock::getBlockIndex)).toList();
        List<OrganizedDocumentBlock> parents = ordered.stream().filter(value -> value.getBlockType() == OrganizedBlockType.TOPIC).toList();
        List<TopicSnapshot> output = new ArrayList<>();
        if (!parents.isEmpty()) {
            int index = 0;
            for (OrganizedDocumentBlock parent : parents) {
                List<OrganizedDocumentBlock> children = ordered.stream().filter(value -> parent.getId().equals(value.getParentBlockId())).toList();
                List<OrganizedDocumentBlock> source = children.isEmpty() ? List.of(parent) : children;
                output.add(new TopicSnapshot(parent.getId(), index++, title(parent.getTopicTitle()), source.stream().map(this::snapshot).toList()));
            }
            return output;
        }
        List<OrganizedDocumentBlock> semantic = ordered.stream().filter(value -> value.getBlockType() == OrganizedBlockType.QA_PAIR || value.getBlockType() == OrganizedBlockType.NARRATIVE || value.getBlockType() == OrganizedBlockType.TOPIC).toList();
        List<OrganizedDocumentBlock> current = new ArrayList<>(); String currentTitle = null; int index = 0;
        for (OrganizedDocumentBlock block : semantic) {
            String title = title(block.getTopicTitle());
            if (!current.isEmpty() && !Objects.equals(currentTitle, title)) {
                output.add(new TopicSnapshot(current.get(0).getId(), index++, currentTitle, current.stream().map(this::snapshot).toList())); current = new ArrayList<>();
            }
            currentTitle = title; current.add(block);
        }
        if (!current.isEmpty()) output.add(new TopicSnapshot(current.get(0).getId(), index, currentTitle, current.stream().map(this::snapshot).toList()));
        return output;
    }

    private UnitSnapshot snapshot(OrganizedDocumentBlock block) {
        return new UnitSnapshot(block.getId(), block.getTextContent(), block.getSpeakerLabel(), block.getSpeakerIds(), block.getStartMs(), block.getEndMs(), block.getSourceSegmentIds(), block.getSourceFragments(), block.getBlockType());
    }

    private List<Fragment> fragments(String document, String idsDocument, String legacySpeaker, long start, long end, String text) {
        try {
            if (document != null && !document.isBlank()) {
                List<Fragment> values = mapper.readValue(document, new TypeReference<>() { });
                if (!values.isEmpty()) return List.copyOf(values);
            }
            List<String> ids = mapper.readValue(idsDocument, new TypeReference<>() { });
            return ids.stream().map(id -> new Fragment(id, legacySpeaker, start, end, text)).toList();
        } catch (Exception exception) { throw new IllegalStateException("Organized block has invalid source references", exception); }
    }

    private static String title(String value) { return value == null || value.isBlank() ? "整理片段" : value; }

    public record TopicSnapshot(String id, int topicIndex, String title, List<UnitSnapshot> units) { }
    public record UnitSnapshot(String blockId, String text, String speakerLabel, String speakerIds, long startMs, long endMs,
                               String sourceSegmentIds, String sourceFragments, OrganizedBlockType blockType) { }
    public record TopicReference(String id, String title, int topicIndex) { }
    public record EmbeddedChunk(List<TopicReference> topics, String topicTitle, long startMs, long endMs, List<String> segmentIds, List<String> contextSegmentIds,
                                List<String> blockIds, List<String> speakerIds, List<Fragment> sourceFragments, String content,
                                int tokenCount, List<Double> vector, boolean oversized) { }
    public record Fragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    private record Measured(TextEmbeddingClient.EmbeddedDocument embedded, int tokens) { }
    private static final class TopicData {
        private final TopicSnapshot topic; private final List<Unit> units;
        private TopicData(TopicSnapshot topic, List<Unit> units) { this.topic = topic; this.units = new ArrayList<>(units); }
    }
    private static final class Unit {
        private final String topicId; private final int topicIndex; private final String blockId; private final String topicTitle; private final String text;
        private final long startMs; private final long endMs; private final List<Fragment> fragments; private final List<String> segmentIds;
        private Unit(String topicId, int topicIndex, String blockId, String topicTitle, String text, long startMs, long endMs, List<Fragment> fragments) {
            this.topicId = topicId; this.topicIndex = topicIndex; this.blockId = blockId; this.topicTitle = topicTitle; this.text = text;
            this.startMs = startMs; this.endMs = endMs; this.fragments = fragments; this.segmentIds = fragments.stream().map(Fragment::segmentId).toList();
        }
    }
}
