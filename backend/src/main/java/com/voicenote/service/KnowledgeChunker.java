package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.provider.ProviderException;
import com.voicenote.provider.TextEmbeddingClient;
import org.springframework.stereotype.Component;
import java.util.*;

/** Splits only at persisted semantic units and uses the embedding provider's actual token accounting. */
@Component
public class KnowledgeChunker {
    private static final int MIN_ACCEPTED_TOKENS = 200;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final TextEmbeddingClient embeddings;

    public KnowledgeChunker(ObjectMapper mapper, AppProperties properties, TextEmbeddingClient embeddings) {
        this.mapper = mapper; this.properties = properties; this.embeddings = embeddings;
    }

    public List<EmbeddedChunk> build(String documentTitle, List<OrganizedDocumentBlock> blocks) {
        List<Unit> units = semanticUnits(blocks);
        if (units.isEmpty()) return List.of();
        List<EmbeddedChunk> output = new ArrayList<>();
        List<Unit> current = new ArrayList<>(); TextEmbeddingClient.EmbeddedDocument currentEmbedding = null;
        Unit prior = null; int cursor = 0;
        int target = Math.max(200, properties.getKnowledge().getChunkTargetTokens());
        int maximum = Math.max(target, properties.getKnowledge().getChunkMaxTokens());
        while (cursor < units.size()) {
            Unit next = units.get(cursor);
            if (!current.isEmpty() && !current.get(0).topicTitle.equals(next.topicTitle)) {
                output.add(toChunk(documentTitle, current, prior, currentEmbedding, false)); prior = current.get(current.size() - 1); current = new ArrayList<>(); currentEmbedding = null; continue;
            }
            List<Unit> candidate = new ArrayList<>(current); candidate.add(next);
            TextEmbeddingClient.EmbeddedDocument embedded = measure(render(documentTitle, candidate, overlap(prior, candidate)));
            int tokens = requiredTokens(embedded);
            if (tokens <= maximum && (tokens <= target || current.isEmpty() || requiredTokens(currentEmbedding) < MIN_ACCEPTED_TOKENS)) {
                current = candidate; currentEmbedding = embedded; cursor++; continue;
            }
            if (!current.isEmpty()) {
                output.add(toChunk(documentTitle, current, prior, currentEmbedding, false)); prior = current.get(current.size() - 1); current = new ArrayList<>(); currentEmbedding = null; continue;
            }
            if (!next.atomic()) {
                List<Unit> atoms = next.atomicUnits();
                if (atoms.size() > 1) { units.remove(cursor); units.addAll(cursor, atoms); continue; }
            }
            current = List.of(next); currentEmbedding = embedded; cursor++;
            output.add(toChunk(documentTitle, current, prior, currentEmbedding, true)); prior = next; current = new ArrayList<>(); currentEmbedding = null;
        }
        if (!current.isEmpty()) output.add(toChunk(documentTitle, current, prior, currentEmbedding, false));
        return List.copyOf(output);
    }

    private TextEmbeddingClient.EmbeddedDocument measure(String text) { return embeddings.embedDocumentWithUsage(text); }
    private static int requiredTokens(TextEmbeddingClient.EmbeddedDocument value) {
        if (value == null || value.promptTokens() == null) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_USAGE_MISSING", "Embedding provider did not report prompt token usage");
        return value.promptTokens();
    }
    private static Unit overlap(Unit prior, List<Unit> candidate) {
        return prior != null && prior.topicTitle.equals(candidate.get(0).topicTitle) ? prior : null;
    }
    private static String render(String title, List<Unit> units, Unit context) {
        Unit first = units.get(0); StringBuilder output = new StringBuilder("# ").append(title).append("\n## ").append(first.topicTitle).append('\n');
        if (context != null) output.append("[上下文] ").append(context.text).append('\n');
        for (Unit unit : units) output.append(unit.text).append('\n');
        return output.toString();
    }
    private static EmbeddedChunk toChunk(String title, List<Unit> units, Unit prior, TextEmbeddingClient.EmbeddedDocument embedded, boolean oversized) {
        Unit context = overlap(prior, units); LinkedHashMap<String, Fragment> fragments = new LinkedHashMap<>();
        if (context != null) context.fragments.forEach(value -> fragments.put(value.segmentId, value));
        for (Unit unit : units) unit.fragments.forEach(value -> fragments.put(value.segmentId, value));
        List<String> primaryIds = units.stream().flatMap(unit -> unit.segmentIds.stream()).distinct().toList();
        List<String> contextIds = context == null ? List.of() : context.segmentIds;
        List<String> blockIds = units.stream().map(unit -> unit.blockId).distinct().toList();
        List<String> speakerIds = fragments.values().stream().map(Fragment::speakerId).filter(Objects::nonNull).distinct().toList();
        return new EmbeddedChunk(units.get(0).topicTitle, units.get(0).startMs, units.get(units.size() - 1).endMs, primaryIds, contextIds, blockIds,
                speakerIds, List.copyOf(fragments.values()), render(title, units, context), requiredTokens(embedded), embedded.vector(), oversized);
    }
    private List<Unit> semanticUnits(List<OrganizedDocumentBlock> blocks) {
        List<OrganizedDocumentBlock> children = blocks.stream().filter(block -> block.getParentBlockId() != null).toList();
        List<OrganizedDocumentBlock> source = children.isEmpty() ? blocks.stream().filter(block -> block.getBlockType() == OrganizedBlockType.TOPIC).toList() : children;
        List<Unit> output = new ArrayList<>();
        for (OrganizedDocumentBlock block : source) {
            if (block.getBlockType() != OrganizedBlockType.QA_PAIR && block.getBlockType() != OrganizedBlockType.NARRATIVE && block.getBlockType() != OrganizedBlockType.TOPIC) continue;
            List<Fragment> fragments = fragments(block.getSourceFragments(), block.getSourceSegmentIds(), block.getSpeakerLabel(), block.getStartMs(), block.getEndMs(), block.getTextContent());
            output.add(new Unit(block.getId(), block.getTopicTitle() == null ? "整理片段" : block.getTopicTitle(), block.getTextContent(), block.getStartMs(), block.getEndMs(), fragments, false));
        }
        return output;
    }
    private List<Fragment> fragments(String document, String idsDocument, String legacySpeaker, long start, long end, String text) {
        try {
            if (document != null && !document.isBlank()) {
                JsonNode values = mapper.readTree(document); List<Fragment> output = new ArrayList<>();
                for (JsonNode value : values) output.add(new Fragment(value.path("segmentId").asText(), value.path("speakerId").asText(null), value.path("startMs").asLong(), value.path("endMs").asLong(), value.path("text").asText()));
                if (!output.isEmpty()) return List.copyOf(output);
            }
            JsonNode ids = mapper.readTree(idsDocument); List<Fragment> output = new ArrayList<>();
            for (JsonNode id : ids) output.add(new Fragment(id.asText(), legacySpeaker, start, end, text));
            return List.copyOf(output);
        } catch (Exception exception) { throw new IllegalStateException("Organized block has invalid source references", exception); }
    }

    public record EmbeddedChunk(String topicTitle, long startMs, long endMs, List<String> segmentIds, List<String> contextSegmentIds,
                                List<String> blockIds, List<String> speakerIds, List<Fragment> sourceFragments, String content,
                                int tokenCount, List<Double> vector, boolean oversized) { }
    public record Fragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    private static final class Unit {
        private final String blockId; private final String topicTitle; private final String text; private final long startMs; private final long endMs; private final List<Fragment> fragments; private final boolean atomic; private final List<String> segmentIds;
        private Unit(String blockId, String topicTitle, String text, long startMs, long endMs, List<Fragment> fragments, boolean atomic) {
            this.blockId = blockId; this.topicTitle = topicTitle; this.text = text; this.startMs = startMs; this.endMs = endMs; this.fragments = fragments; this.atomic = atomic; this.segmentIds = fragments.stream().map(Fragment::segmentId).toList();
        }
        private boolean atomic() { return atomic; }
        private List<Unit> atomicUnits() {
            return fragments.stream().map(fragment -> new Unit(blockId, topicTitle, fragment.speakerId() == null ? fragment.text() : fragment.speakerId() + ": " + fragment.text(),
                    fragment.startMs(), fragment.endMs(), List.of(fragment), true)).toList();
        }
    }
}
