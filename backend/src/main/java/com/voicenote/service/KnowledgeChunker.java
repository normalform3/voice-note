package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.ChunkProfile;
import com.voicenote.domain.KnowledgeChunkKind;
import com.voicenote.domain.KnowledgeTopic;
import com.voicenote.domain.OrganizedBlockType;
import com.voicenote.domain.OrganizedDocumentBlock;
import com.voicenote.domain.SceneType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Builds traceable, scene-aware chunks from persisted topic snapshots without calling an embedding provider. */
@Component
public class KnowledgeChunker {
    private static final int SHORT_DOCUMENT_TOKENS = 600;
    private static final double MONOLOGUE_SWITCH_THRESHOLD = 0.15;
    private static final int HARD_SPLIT_OVERLAP_TOKENS = 80;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？；.!?;])");

    private final ObjectMapper mapper;
    private final KnowledgeTokenEstimator tokens;

    public KnowledgeChunker(ObjectMapper mapper, KnowledgeTokenEstimator tokens) {
        this.mapper = mapper;
        this.tokens = tokens;
    }

    /** Compatibility entrypoint for callers and tests that do not supply recording metadata. */
    public List<EmbeddedChunk> build(String documentTitle, List<OrganizedDocumentBlock> blocks) {
        return build(new ChunkingContext(documentTitle, SceneType.OTHER, null, null), blocks);
    }

    public List<EmbeddedChunk> build(ChunkingContext context, List<OrganizedDocumentBlock> blocks) {
        return buildTopics(normalize(context), topicsFromBlocks(blocks));
    }

    public List<TopicSnapshot> snapshotTopics(List<OrganizedDocumentBlock> blocks) {
        return List.copyOf(topicsFromBlocks(blocks));
    }

    /** Compatibility entrypoint for index snapshots created before scene-aware chunking. */
    public List<EmbeddedChunk> buildFromTopics(String documentTitle, List<KnowledgeTopic> topics) {
        return buildFromTopics(new ChunkingContext(documentTitle, SceneType.OTHER, null, null), topics);
    }

    public List<EmbeddedChunk> buildFromTopics(ChunkingContext context, List<KnowledgeTopic> topics) {
        List<TopicSnapshot> snapshots = new ArrayList<>();
        for (KnowledgeTopic topic : topics) {
            try {
                List<UnitSnapshot> units = mapper.readValue(topic.getSourceUnitSnapshots(), new TypeReference<>() { });
                snapshots.add(new TopicSnapshot(topic.getId(), topic.getTopicIndex(), topic.getTitle(), units));
            } catch (Exception exception) {
                throw new IllegalStateException("Knowledge topic has invalid unit snapshots", exception);
            }
        }
        return buildTopics(normalize(context), snapshots);
    }

    public ChunkProfile resolveProfile(ChunkingContext context, List<TopicSnapshot> snapshots) {
        ChunkingContext normalized = normalize(context);
        List<TopicData> topics = topicData(snapshots);
        List<Unit> units = units(topics);
        if (tokens.estimate(renderDisplay(normalized.documentTitle(), units, null)) <= SHORT_DOCUMENT_TOKENS) {
            return ChunkProfile.SHORT_DOCUMENT;
        }
        if (normalized.sceneType() == SceneType.INTERVIEW) return ChunkProfile.INTERVIEW_QA;
        if (normalized.sceneType() == SceneType.MEETING) return ChunkProfile.MEETING_DISCUSSION;

        List<String> speakers = units.stream().flatMap(value -> value.fragments.stream())
                .map(Fragment::speakerId).filter(KnowledgeChunker::knownSpeaker).toList();
        if (new LinkedHashSet<>(speakers).size() <= 1 || speakerSwitchRate(speakers) < MONOLOGUE_SWITCH_THRESHOLD) {
            return ChunkProfile.MONOLOGUE;
        }
        return ChunkProfile.CONVERSATION;
    }

    private List<EmbeddedChunk> buildTopics(ChunkingContext context, List<TopicSnapshot> snapshots) {
        List<TopicData> topics = topicData(snapshots);
        if (topics.isEmpty()) return List.of();
        ChunkProfile profile = resolveProfile(context, snapshots);
        if (profile == ChunkProfile.SHORT_DOCUMENT) {
            return List.of(toChunk(context, profile, KnowledgeChunkKind.DOCUMENT, units(topics), List.of(), null, false));
        }
        List<EmbeddedChunk> output = new ArrayList<>();
        for (TopicData topic : topics) output.addAll(splitTopic(context, profile, topic));
        return List.copyOf(output);
    }

    private List<TopicData> topicData(List<TopicSnapshot> snapshots) {
        return snapshots.stream().sorted(Comparator.comparingInt(TopicSnapshot::topicIndex)).map(this::toTopic)
                .filter(value -> !value.units.isEmpty()).toList();
    }

    private List<EmbeddedChunk> splitTopic(ChunkingContext context, ChunkProfile profile, TopicData topic) {
        List<EmbeddedChunk> output = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        List<Unit> semanticUnits = new ArrayList<>();
        for (Unit unit : topic.units) {
            if (unit.blockType == OrganizedBlockType.NARRATIVE
                    && (profile == ChunkProfile.MEETING_DISCUSSION || profile == ChunkProfile.CONVERSATION)) semanticUnits.addAll(turnUnits(unit));
            else semanticUnits.add(unit);
        }
        for (Unit unit : semanticUnits) {
            if (unit.blockType == OrganizedBlockType.QA_PAIR) {
                flush(context, profile, current, output);
                current.clear();
                EmbeddedChunk qa = toChunk(context, profile, KnowledgeChunkKind.QA, List.of(unit), List.of(), null, false);
                if (qa.tokenCount() <= profile.maximumTokens()) output.add(qa);
                else output.addAll(splitQa(context, profile, unit));
                continue;
            }

            List<Unit> candidate = new ArrayList<>(current);
            candidate.add(unit);
            EmbeddedChunk measured = toChunk(context, profile, KnowledgeChunkKind.NARRATIVE, candidate, List.of(), null, false);
            if (!current.isEmpty() && measured.tokenCount() > profile.targetTokens()) {
                flush(context, profile, current, output);
                current.clear();
            }
            EmbeddedChunk single = toChunk(context, profile, KnowledgeChunkKind.NARRATIVE, List.of(unit), List.of(), null, false);
            if (single.tokenCount() > profile.maximumTokens()) {
                flush(context, profile, current, output);
                current.clear();
                output.addAll(splitLongUnit(context, profile, unit, KnowledgeChunkKind.NARRATIVE, List.of(), null));
            } else {
                current.add(unit);
            }
        }
        flush(context, profile, current, output);
        return output;
    }

    private void flush(ChunkingContext context, ChunkProfile profile, List<Unit> units, List<EmbeddedChunk> output) {
        if (!units.isEmpty()) output.add(toChunk(context, profile, KnowledgeChunkKind.NARRATIVE, List.copyOf(units), List.of(), null, false));
    }

    private List<EmbeddedChunk> splitQa(ChunkingContext context, ChunkProfile profile, Unit unit) {
        if (unit.fragments.size() < 2) {
            return splitLongUnit(context, profile, unit, KnowledgeChunkKind.ANSWER_PART, List.of(), firstLine(unit.text));
        }
        List<Unit> turns = turnUnits(unit);
        if (turns.size() < 2) {
            int answerStart = 1;
            String questionSpeaker = unit.fragments.get(0).speakerId();
            while (answerStart < unit.fragments.size() && Objects.equals(questionSpeaker, unit.fragments.get(answerStart).speakerId())) answerStart++;
            if (answerStart == unit.fragments.size()) answerStart = 1;
            List<Fragment> question = List.copyOf(unit.fragments.subList(0, answerStart));
            List<Fragment> answers = List.copyOf(unit.fragments.subList(answerStart, unit.fragments.size()));
            if (answers.isEmpty()) return List.of(toChunk(context, profile, KnowledgeChunkKind.QA, List.of(unit), List.of(), null, true));
            String questionText = firstLine(unit.text);
            List<Unit> answerUnits = new ArrayList<>();
            for (Fragment fragment : answers) answerUnits.addAll(fragmentUnits(unit, fragment, profile, context,
                    KnowledgeChunkKind.ANSWER_PART, question, questionText));
            return groupParts(context, profile, KnowledgeChunkKind.ANSWER_PART, answerUnits, question, questionText);
        }
        Unit questionTurn = turns.get(0);
        List<Fragment> question = questionTurn.fragments;
        List<Unit> answerTurns = turns.subList(1, turns.size());
        if (answerTurns.isEmpty()) {
            return List.of(toChunk(context, profile, KnowledgeChunkKind.QA, List.of(unit), List.of(), null, true));
        }
        String questionText = questionTurn.text;
        List<Unit> answerUnits = new ArrayList<>();
        for (Unit answer : answerTurns) {
            EmbeddedChunk measured = toChunk(context, profile, KnowledgeChunkKind.ANSWER_PART, List.of(answer), question, questionText, false);
            if (measured.tokenCount() <= profile.maximumTokens()) answerUnits.add(answer);
            else answerUnits.addAll(splitLongUnitParts(context, profile, answer, KnowledgeChunkKind.ANSWER_PART, question, questionText));
        }
        return groupParts(context, profile, KnowledgeChunkKind.ANSWER_PART, answerUnits, question, questionText);
    }

    private List<EmbeddedChunk> splitLongUnit(ChunkingContext context, ChunkProfile profile, Unit unit, KnowledgeChunkKind kind,
                                               List<Fragment> contextFragments, String contextText) {
        if (unit.fragments.isEmpty()) {
            return List.of(toChunk(context, profile, kind, List.of(unit), contextFragments, contextText, true));
        }
        List<Unit> parts = splitLongUnitParts(context, profile, unit, kind, contextFragments, contextText);
        return groupParts(context, profile, kind, parts, contextFragments, contextText);
    }

    private List<Unit> splitLongUnitParts(ChunkingContext context, ChunkProfile profile, Unit unit, KnowledgeChunkKind kind,
                                          List<Fragment> contextFragments, String contextText) {
        List<Unit> parts = new ArrayList<>();
        for (Fragment fragment : unit.fragments) parts.addAll(fragmentUnits(unit, fragment, profile, context, kind, contextFragments, contextText));
        return parts;
    }

    private List<Unit> fragmentUnits(Unit template, Fragment fragment, ChunkProfile profile, ChunkingContext context,
                                     KnowledgeChunkKind kind, List<Fragment> contextFragments, String contextText) {
        Unit whole = fragmentUnit(template, fragment, fragment.text());
        if (toChunk(context, profile, kind, List.of(whole), contextFragments, contextText, false).tokenCount() <= profile.maximumTokens()) {
            return List.of(whole);
        }
        int headerTokens = tokens.estimate(denseHeader(context, profile, kind, template.topicTitle, List.of(fragment.speakerId()))
                + Objects.toString(contextText, ""));
        int bodyBudget = Math.max(100, profile.maximumTokens() - headerTokens);
        return splitText(fragment.text(), bodyBudget).stream().map(text -> fragmentUnit(template,
                new Fragment(fragment.segmentId(), fragment.speakerId(), fragment.startMs(), fragment.endMs(), text), text)).toList();
    }

    private List<EmbeddedChunk> groupParts(ChunkingContext context, ChunkProfile profile, KnowledgeChunkKind kind, List<Unit> parts,
                                            List<Fragment> contextFragments, String contextText) {
        List<EmbeddedChunk> output = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        for (Unit part : parts) {
            Set<String> currentSegmentIds = current.stream().flatMap(value -> value.fragments.stream()).map(Fragment::segmentId).collect(java.util.stream.Collectors.toSet());
            boolean repeatsHardSplitSegment = part.fragments.stream().map(Fragment::segmentId).anyMatch(currentSegmentIds::contains);
            if (repeatsHardSplitSegment && !current.isEmpty()) {
                output.add(toChunk(context, profile, kind, List.copyOf(current), contextFragments, contextText, false));
                current.clear();
            }
            List<Unit> candidate = new ArrayList<>(current); candidate.add(part);
            EmbeddedChunk measured = toChunk(context, profile, kind, candidate, contextFragments, contextText, false);
            if (!current.isEmpty() && measured.tokenCount() > profile.targetTokens()) {
                output.add(toChunk(context, profile, kind, List.copyOf(current), contextFragments, contextText, false));
                current.clear();
            }
            current.add(part);
        }
        if (!current.isEmpty()) output.add(toChunk(context, profile, kind, List.copyOf(current), contextFragments, contextText, false));
        return output;
    }

    private List<String> splitText(String text, int tokenLimit) {
        List<String> output = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(text)) {
            if (sentence.isBlank()) continue;
            String candidate = current + sentence;
            if (!current.isEmpty() && tokens.estimate(candidate) > tokenLimit) {
                output.add(current.toString()); current = new StringBuilder();
            }
            if (tokens.estimate(sentence) > tokenLimit) output.addAll(hardSplit(sentence, tokenLimit));
            else current.append(sentence);
        }
        if (!current.isEmpty()) output.add(current.toString());
        return output.isEmpty() ? hardSplit(text, tokenLimit) : List.copyOf(output);
    }

    private List<String> hardSplit(String text, int tokenLimit) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 0) return List.of("");
        List<String> output = new ArrayList<>();
        int start = 0;
        while (start < codePoints.length) {
            int end = start + 1;
            while (end <= codePoints.length && tokens.estimate(new String(codePoints, start, end - start)) <= tokenLimit) end++;
            end = Math.max(start + 1, end - 1);
            output.add(new String(codePoints, start, end - start));
            if (end >= codePoints.length) break;
            int overlapStart = end;
            while (overlapStart > start && tokens.estimate(new String(codePoints, overlapStart - 1, end - overlapStart + 1)) <= HARD_SPLIT_OVERLAP_TOKENS) overlapStart--;
            start = Math.max(start + 1, overlapStart);
        }
        return List.copyOf(output);
    }

    private List<Unit> turnUnits(Unit unit) {
        if (unit.fragments.isEmpty()) return List.of(unit);
        List<List<Fragment>> fragmentTurns = new ArrayList<>();
        for (Fragment fragment : unit.fragments) {
            if (fragmentTurns.isEmpty() || !Objects.equals(fragmentTurns.get(fragmentTurns.size() - 1).get(0).speakerId(), fragment.speakerId())) {
                fragmentTurns.add(new ArrayList<>());
            }
            fragmentTurns.get(fragmentTurns.size() - 1).add(fragment);
        }
        List<String> lines = Arrays.stream(unit.text.split("\\R")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (lines.size() != fragmentTurns.size()) return List.of(unit);
        List<Unit> output = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            List<Fragment> fragments = List.copyOf(fragmentTurns.get(index));
            output.add(new Unit(unit.topicId, unit.topicIndex, unit.blockId, unit.topicTitle, lines.get(index),
                    fragments.get(0).startMs(), fragments.get(fragments.size() - 1).endMs(), fragments, unit.blockType));
        }
        return List.copyOf(output);
    }

    private Unit fragmentUnit(Unit template, Fragment fragment, String text) {
        String speaker = fragment.speakerId() == null || fragment.speakerId().isBlank() ? "SPEAKER_UNKNOWN" : fragment.speakerId();
        return new Unit(template.topicId, template.topicIndex, template.blockId, template.topicTitle, speaker + "：" + text,
                fragment.startMs(), fragment.endMs(), List.of(fragment), template.blockType);
    }

    private EmbeddedChunk toChunk(ChunkingContext context, ChunkProfile profile, KnowledgeChunkKind kind, List<Unit> units,
                                  List<Fragment> contextFragments, String contextText, boolean forceOversized) {
        if (units.isEmpty()) throw new IllegalArgumentException("Knowledge chunk requires at least one unit");
        LinkedHashMap<String, Fragment> fragments = new LinkedHashMap<>();
        LinkedHashMap<String, Fragment> primaryFragments = new LinkedHashMap<>();
        contextFragments.forEach(value -> fragments.put(value.segmentId(), value));
        units.forEach(unit -> unit.fragments.forEach(value -> { fragments.put(value.segmentId(), value); primaryFragments.put(value.segmentId(), value); }));
        List<String> contextIds = contextFragments.stream().map(Fragment::segmentId).distinct().toList();
        List<String> segmentIds = List.copyOf(fragments.keySet());
        List<String> blockIds = units.stream().map(unit -> unit.blockId).filter(Objects::nonNull).distinct().toList();
        List<String> speakerIds = fragments.values().stream().map(Fragment::speakerId).filter(KnowledgeChunker::knownSpeaker).distinct().toList();
        List<TopicReference> topics = units.stream().collect(java.util.stream.Collectors.toMap(unit -> unit.topicId,
                unit -> new TopicReference(unit.topicId, unit.topicTitle, unit.topicIndex), (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        String topicTitle = topics.stream().map(TopicReference::title).distinct().reduce((left, right) -> left + " / " + right).orElse("整理片段");
        String body = contextText == null ? null : contextText + "\n" + units.stream().map(value -> value.text).collect(java.util.stream.Collectors.joining("\n"));
        String display = renderDisplay(context.documentTitle(), units, body);
        String dense = denseHeader(context, profile, kind, topicTitle, speakerIds) + display;
        String lexical = dense + "\n原始转写：\n" + fragments.values().stream().map(Fragment::text).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
        int tokenCount = tokens.estimate(dense);
        long start = primaryFragments.isEmpty() ? units.get(0).startMs : primaryFragments.values().stream().mapToLong(Fragment::startMs).min().orElse(units.get(0).startMs);
        long end = primaryFragments.isEmpty() ? units.get(units.size() - 1).endMs : primaryFragments.values().stream().mapToLong(Fragment::endMs).max().orElse(units.get(units.size() - 1).endMs);
        return new EmbeddedChunk(topics, topicTitle, start, end, segmentIds, contextIds, blockIds, speakerIds,
                List.copyOf(fragments.values()), display, dense, lexical, tokenCount, forceOversized || tokenCount > profile.maximumTokens(), profile, kind);
    }

    private static String renderDisplay(String title, List<Unit> units, String overrideBody) {
        StringBuilder output = new StringBuilder("# ").append(title).append('\n');
        if (overrideBody != null) return output.append("## ").append(units.get(0).topicTitle).append('\n').append(overrideBody).append('\n').toString();
        String topicId = null;
        for (Unit unit : units) {
            if (!Objects.equals(topicId, unit.topicId)) {
                output.append("## ").append(unit.topicTitle).append('\n'); topicId = unit.topicId;
            }
            output.append(unit.text).append('\n');
        }
        return output.toString();
    }

    private static String denseHeader(ChunkingContext context, ChunkProfile profile, KnowledgeChunkKind kind,
                                      String topicTitle, List<String> speakers) {
        StringBuilder output = new StringBuilder();
        output.append("场景：").append(context.sceneType()).append("\n画像：").append(profile).append("\n类型：").append(kind).append('\n');
        if (context.occurredAt() != null) output.append("日期：").append(context.occurredAt()).append('\n');
        if (context.subject() != null && !context.subject().isBlank()) output.append("主题描述：").append(context.subject().trim()).append('\n');
        output.append("Topic：").append(topicTitle).append('\n');
        if (!speakers.isEmpty()) output.append("说话人：").append(String.join(", ", speakers)).append('\n');
        return output.toString();
    }

    private TopicData toTopic(TopicSnapshot topic) {
        List<Unit> units = new ArrayList<>();
        for (UnitSnapshot source : topic.units()) {
            List<Fragment> fragments = fragments(source.sourceFragments(), source.sourceSegmentIds(), source.speakerLabel(), source.startMs(), source.endMs(), source.text());
            units.add(new Unit(topic.id(), topic.topicIndex(), source.blockId(), topic.title(), source.text(), source.startMs(), source.endMs(), fragments,
                    source.blockType() == null ? OrganizedBlockType.NARRATIVE : source.blockType()));
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
        List<OrganizedDocumentBlock> semantic = ordered.stream().filter(value -> value.getBlockType() == OrganizedBlockType.QA_PAIR
                || value.getBlockType() == OrganizedBlockType.NARRATIVE || value.getBlockType() == OrganizedBlockType.TOPIC).toList();
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
        return new UnitSnapshot(block.getId(), block.getTextContent(), block.getSpeakerLabel(), block.getSpeakerIds(), block.getStartMs(), block.getEndMs(),
                block.getSourceSegmentIds(), block.getSourceFragments(), block.getBlockType());
    }

    private List<Fragment> fragments(String document, String idsDocument, String legacySpeaker, long start, long end, String text) {
        try {
            if (document != null && !document.isBlank()) {
                List<Fragment> values = mapper.readValue(document, new TypeReference<>() { });
                if (!values.isEmpty()) return List.copyOf(values);
            }
            if (idsDocument == null || idsDocument.isBlank()) return List.of();
            List<String> ids = mapper.readValue(idsDocument, new TypeReference<>() { });
            return ids.stream().map(id -> new Fragment(id, legacySpeaker, start, end, text)).toList();
        } catch (Exception exception) { throw new IllegalStateException("Organized block has invalid source references", exception); }
    }

    private static List<Unit> units(List<TopicData> topics) { return topics.stream().flatMap(value -> value.units.stream()).toList(); }
    private static String firstLine(String value) {
        int newline = value == null ? -1 : value.indexOf('\n');
        return value == null ? "问题" : newline < 0 ? value : value.substring(0, newline);
    }
    private static String title(String value) { return value == null || value.isBlank() ? "整理片段" : value; }
    private static boolean knownSpeaker(String value) { return value != null && !value.isBlank() && !"SPEAKER_UNKNOWN".equals(value); }
    private static double speakerSwitchRate(List<String> speakers) {
        if (speakers.size() < 2) return 0;
        int switches = 0;
        for (int index = 1; index < speakers.size(); index++) if (!Objects.equals(speakers.get(index - 1), speakers.get(index))) switches++;
        return (double) switches / (speakers.size() - 1);
    }
    private static ChunkingContext normalize(ChunkingContext value) {
        if (value == null) return new ChunkingContext("录音", SceneType.OTHER, null, null);
        return new ChunkingContext(value.documentTitle() == null || value.documentTitle().isBlank() ? "录音" : value.documentTitle(),
                value.sceneType() == null ? SceneType.OTHER : value.sceneType(), value.subject(), value.occurredAt());
    }

    public record ChunkingContext(String documentTitle, SceneType sceneType, String subject, Instant occurredAt) { }
    public record TopicSnapshot(String id, int topicIndex, String title, List<UnitSnapshot> units) { }
    public record UnitSnapshot(String blockId, String text, String speakerLabel, String speakerIds, long startMs, long endMs,
                               String sourceSegmentIds, String sourceFragments, OrganizedBlockType blockType) { }
    public record TopicReference(String id, String title, int topicIndex) { }
    public record EmbeddedChunk(List<TopicReference> topics, String topicTitle, long startMs, long endMs, List<String> segmentIds,
                                List<String> contextSegmentIds, List<String> blockIds, List<String> speakerIds, List<Fragment> sourceFragments,
                                String content, String denseText, String lexicalText, int tokenCount, boolean oversized,
                                ChunkProfile profile, KnowledgeChunkKind kind) { }
    public record Fragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }

    private static final class TopicData {
        private final TopicSnapshot topic; private final List<Unit> units;
        private TopicData(TopicSnapshot topic, List<Unit> units) { this.topic = topic; this.units = new ArrayList<>(units); }
    }
    private static final class Unit {
        private final String topicId; private final int topicIndex; private final String blockId; private final String topicTitle; private final String text;
        private final long startMs; private final long endMs; private final List<Fragment> fragments; private final OrganizedBlockType blockType;
        private Unit(String topicId, int topicIndex, String blockId, String topicTitle, String text, long startMs, long endMs,
                     List<Fragment> fragments, OrganizedBlockType blockType) {
            this.topicId = topicId; this.topicIndex = topicIndex; this.blockId = blockId; this.topicTitle = topicTitle; this.text = text;
            this.startMs = startMs; this.endMs = endMs; this.fragments = fragments; this.blockType = blockType;
        }
    }
}
