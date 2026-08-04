package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** Builds an evidence-preserving reading document. The model may organize text, never source identity. */
@Service
public class DocumentOrganizationService {
    private static final String STRUCTURE_STAGE = "STRUCTURE";
    private final OrganizedDocumentRepository documents;
    private final OrganizedDocumentBlockRepository blocks;
    private final TranscriptSegmentRepository segments;
    private final TranscriptionTaskRepository tasks;
    private final OrganizationInvocationRepository invocations;
    private final TranscriptSpeakerService speakers;
    private final OutboxService outbox;
    private final ObjectMapper mapper;

    public DocumentOrganizationService(OrganizedDocumentRepository documents, OrganizedDocumentBlockRepository blocks,
                                       TranscriptSegmentRepository segments, TranscriptionTaskRepository tasks,
                                       OrganizationInvocationRepository invocations, TranscriptSpeakerService speakers,
                                       OutboxService outbox, ObjectMapper mapper) {
        this.documents = documents; this.blocks = blocks; this.segments = segments; this.tasks = tasks; this.invocations = invocations;
        this.speakers = speakers; this.outbox = outbox; this.mapper = mapper;
    }

    @Transactional
    public OrganizedDocument createForTranscript(TranscriptionTask task, AudioBlob audio) {
        return documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .orElseGet(() -> {
                    OrganizedDocument document = documents.save(new OrganizedDocument(task.getOwnerId(), task.getId(), task.getTranscriptVersion(), titleFor(audio.getOriginalFilename())));
                    outbox.enqueue("organized_document", document.getId(), EventType.DOCUMENT_ORGANIZATION_REQUESTED,
                            "{\"taskId\":\"" + task.getId() + "\",\"stage\":\"DOCUMENT_ORGANIZATION\",\"documentId\":\"" + document.getId() + "\"}",
                            "task:" + task.getId() + ":document:" + document.getId());
                    return document;
                });
    }

    @Transactional public void markQueued(String documentId) { documents.findById(documentId).orElseThrow().queue(); }
    @Transactional(readOnly = true) public List<String> queuedDocumentIds() { return documents.findTop20ByStatusOrderByCreatedAtAsc(OrganizedDocumentStatus.QUEUED).stream().map(OrganizedDocument::getId).toList(); }

    @Transactional
    public OrganizationWork claim(String documentId) {
        OrganizedDocument document = documents.findById(documentId).orElse(null);
        if (document == null) return null;
        TranscriptionTask task = tasks.findById(document.getTranscriptionTaskId()).orElseThrow();
        if (task.isCancelled() || !document.begin()) return null;
        return new OrganizationWork(document, segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), document.getTranscriptVersion()));
    }

    @Transactional
    public ModelAction prepareSemantic(OrganizationWork work) {
        String prompt = semanticPrompt(work.document().getTitle(), turns(work.segments()));
        String hash = Hashing.sha256(prompt);
        OrganizationInvocation invocation = invocations.findByOrganizedDocumentIdAndStageName(work.document().getId(), STRUCTURE_STAGE)
                .orElseGet(() -> invocations.save(new OrganizationInvocation(work.document().getId(), STRUCTURE_STAGE, hash)));
        if (invocation.getStatus() == InvocationStatus.SUCCEEDED) return ModelAction.cached(invocation.getResponseDocument());
        if (invocation.getStatus() == InvocationStatus.IN_FLIGHT || invocation.getStatus() == InvocationStatus.UNKNOWN) {
            throw new IllegalStateException("An earlier document-organization model call has an unknown outcome");
        }
        invocation.start(); invocations.save(invocation); return ModelAction.call(prompt);
    }

    @Transactional public void completeSemantic(String documentId, String response) {
        OrganizationInvocation invocation = invocations.findByOrganizedDocumentIdAndStageName(documentId, STRUCTURE_STAGE).orElseThrow();
        invocation.succeed(response); invocations.save(invocation);
    }
    @Transactional public void markSemanticUnknown(String documentId) {
        invocations.findByOrganizedDocumentIdAndStageName(documentId, STRUCTURE_STAGE).ifPresent(value -> { value.unknown(); invocations.save(value); });
    }

    public OrganizationResult organizeSemantic(OrganizationWork work, String rawResponse) {
        List<Turn> allTurns = turns(work.segments());
        try {
            JsonNode root = mapper.readTree(rawResponse);
            if (!root.isObject() || !root.path("topics").isArray()) throw invalid("LLM must return a topics array");
            Map<String, TranscriptSegment> source = work.segments().stream().collect(Collectors.toMap(TranscriptSegment::getId, value -> value, (left, right) -> left, LinkedHashMap::new));
            List<String> expected = allTurns.stream().flatMap(turn -> turn.segmentIds().stream()).toList();
            List<Topic> topicResults = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (JsonNode topic : root.path("topics")) {
                String title = bounded(topic.path("title").asText(null), 512, "Topic title");
                String summary = optionalBounded(topic.path("summary").asText(null), 4_000);
                JsonNode items = topic.path("items");
                if (!items.isArray() || items.isEmpty()) throw invalid("Each topic must include items");
                List<ContentUnit> units = new ArrayList<>(); List<String> topicIds = new ArrayList<>();
                for (JsonNode item : items) {
                    OrganizedBlockType type = parseUnitType(item.path("type").asText(null));
                    String text = bounded(item.path("text").asText(null), 16_000, "Item text");
                    List<String> ids = idList(item.path("sourceSegmentIds"), source, "Item");
                    ensureContiguous(ids, expected, seen.size());
                    seen.addAll(ids); topicIds.addAll(ids);
                    Span span = span(ids, source);
                    units.add(new ContentUnit(type, text, span.startMs(), span.endMs(), List.copyOf(ids), span.speakerIds()));
                }
                Span span = span(topicIds, source);
                String topicText = units.stream().map(ContentUnit::text).collect(Collectors.joining("\n"));
                topicResults.add(new Topic(title, summary, span.startMs(), span.endMs(), List.copyOf(topicIds), List.copyOf(units), topicText));
            }
            if (!seen.equals(expected)) throw invalid("LLM output must cover every source segment exactly once");
            String title = optionalBounded(root.path("title").asText(null), 512);
            if (title == null) title = work.document().getTitle();
            String summary = optionalBounded(root.path("summary").asText(null), 8_000);
            if (summary == null) summary = topicResults.stream().map(Topic::summary).filter(Objects::nonNull).collect(Collectors.joining(" "));
            Set<String> knownSpeakerIds = source.values().stream()
                    .map(TranscriptSegment::getAsrSpeakerId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<RoleSuggestion> suggestions = roleSuggestions(root.path("roleSuggestions"), knownSpeakerIds);
            String plainText = plainText(title, summary, topicResults);
            return new OrganizationResult(title, summary, "LLM", allTurns, List.copyOf(topicResults), plainText, List.copyOf(suggestions));
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw invalid("LLM returned invalid structured document"); }
    }

    @Transactional
    public List<OrganizedDocumentBlock> complete(String documentId, OrganizationResult result, List<TranscriptSegment> source) {
        OrganizedDocument document = documents.findById(documentId).orElseThrow();
        TranscriptionTask task = tasks.findById(document.getTranscriptionTaskId()).orElseThrow();
        if (task.isCancelled()) return List.of();
        try {
            blocks.deleteByOrganizedDocumentId(documentId);
            Map<String, TranscriptSegment> segmentIndex = source.stream().collect(Collectors.toMap(TranscriptSegment::getId, value -> value));
            List<OrganizedDocumentBlock> stored = new ArrayList<>(); int index = 0;
            for (Topic topic : result.topics()) {
                OrganizedDocumentBlock topicBlock = blocks.save(new OrganizedDocumentBlock(documentId, index++, OrganizedBlockType.TOPIC, null, topic.title(), topic.summary(),
                        json(topic.speakerIds()), topic.startMs(), topic.endMs(), json(topic.segmentIds()), fragments(topic.segmentIds(), segmentIndex), topic.text()));
                stored.add(topicBlock);
                for (ContentUnit unit : topic.items()) {
                    stored.add(blocks.save(new OrganizedDocumentBlock(documentId, index++, unit.type(), topicBlock.getId(), topic.title(), null,
                            json(unit.speakerIds()), unit.startMs(), unit.endMs(), json(unit.segmentIds()), fragments(unit.segmentIds(), segmentIndex), unit.text())));
                }
            }
            document.ready(result.title(), result.summary(), result.mode(), mapper.writeValueAsString(result), result.plainText());
            documents.save(document);
            for (RoleSuggestion suggestion : result.roleSuggestions()) speakers.suggest(document.getTranscriptionTaskId(), document.getTranscriptVersion(), suggestion.speakerId(), suggestion.role(), suggestion.confidence());
            return stored;
        } catch (Exception exception) { throw new IllegalStateException("Cannot persist organized document", exception); }
    }

    /** Kept deterministic so disabled or malformed model calls never send raw ASR directly to indexing. */
    static OrganizationResult organize(List<TranscriptSegment> source) {
        List<Turn> turns = turns(source); List<Topic> topics = new ArrayList<>(); TopicBuilder current = null;
        for (Turn turn : turns) {
            if (current == null || turn.startMs() - current.endMs > 30_000 || current.characterCount() + turn.text().length() > 2_400) {
                if (current != null) topics.add(current.build(topics.size() + 1));
                current = new TopicBuilder(turn);
            } else current.append(turn);
        }
        if (current != null) topics.add(current.build(topics.size() + 1));
        return new OrganizationResult("整理文档", null, "FALLBACK", turns, List.copyOf(topics), plainText("整理文档", null, topics), List.of());
    }
    static OrganizationResult fallbackFor(OrganizationWork work) {
        OrganizationResult fallback = organize(work.segments());
        return new OrganizationResult(work.document().getTitle(), fallback.summary(), fallback.mode(), fallback.turns(), fallback.topics(),
                plainText(work.document().getTitle(), fallback.summary(), fallback.topics()), fallback.roleSuggestions());
    }

    static List<Turn> turns(List<TranscriptSegment> source) {
        List<Turn> output = new ArrayList<>(); TurnBuilder current = null;
        for (TranscriptSegment segment : source) {
            String text = clean(segment.getTextContent()); if (text.isBlank()) continue;
            String speaker = segment.getAsrSpeakerId() == null || segment.getAsrSpeakerId().isBlank() ? "SPEAKER_UNKNOWN" : segment.getAsrSpeakerId();
            if (current != null && current.speaker.equals(speaker) && segment.getStartMs() - current.endMs <= 5_000 && current.characterCount() + text.length() <= 2_400) current.append(segment, text);
            else { if (current != null) output.add(current.build()); current = new TurnBuilder(speaker, segment, text); }
        }
        if (current != null) output.add(current.build()); return List.copyOf(output);
    }

    @Transactional public void fail(String documentId, String message) { documents.findById(documentId).ifPresent(document -> { document.fail(message); documents.save(document); }); }
    @Transactional(readOnly = true) public OrganizedDocument ownedDocument(String ownerId, String documentId) {
        return documents.findById(documentId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZED_DOCUMENT_NOT_FOUND", "Organized document was not found"));
    }
    @Transactional(readOnly = true) public List<OrganizedDocumentBlock> ownedBlocks(String ownerId, String documentId) { ownedDocument(ownerId, documentId); return blocks.findByOrganizedDocumentIdOrderByBlockIndex(documentId); }
    @Transactional public void retryForTask(String ownerId, String taskId) {
        OrganizedDocument document = documents.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(ownerId, taskId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZED_DOCUMENT_NOT_FOUND", "Organized document was not found"));
        document.queue(); documents.save(document);
        outbox.enqueue("organized_document", document.getId(), EventType.DOCUMENT_ORGANIZATION_REQUESTED,
                "{\"taskId\":\"" + taskId + "\",\"stage\":\"DOCUMENT_ORGANIZATION\",\"documentId\":\"" + document.getId() + "\"}",
                "task:" + taskId + ":document:" + document.getId() + ":retry:" + System.nanoTime());
    }
    @Transactional public String recoverForTask(String taskId) {
        return documents.findTopByTranscriptionTaskIdOrderByUpdatedAtDesc(taskId).filter(document -> document.recover()).map(document -> { documents.save(document); return document.getId(); }).orElse(null);
    }

    private static String semanticPrompt(String fallbackTitle, List<Turn> turns) {
        String source = turns.stream().map(turn -> "TURN speaker=" + turn.speaker() + " time=" + turn.startMs() + "-" + turn.endMs() + "ms ids=" + turn.segmentIds() + "\n" + turn.text()).collect(Collectors.joining("\n\n"));
        return "Organize this speaker-labelled meeting or interview transcript. Preserve meaning; readable item text may remove filler words but must not add facts. " +
                "Return JSON only: {\"title\":string,\"summary\":string,\"topics\":[{\"title\":string,\"summary\":string,\"items\":[{\"type\":\"QA_PAIR\"|\"NARRATIVE\",\"sourceSegmentIds\":[string],\"text\":string}]}],\"roleSuggestions\":[{\"speakerId\":string,\"role\":\"INTERVIEWER\"|\"CANDIDATE\"|\"PARTICIPANT\"|\"UNKNOWN\",\"confidence\":number}]}. " +
                "Every source segment ID must appear exactly once across items, in source order. Do not output timestamps or invent speakers. Fallback title: " + fallbackTitle + "\n\n" + source;
    }
    private static List<RoleSuggestion> roleSuggestions(JsonNode node, Set<String> knownSpeakerIds) {
        if (!node.isArray()) return List.of(); List<RoleSuggestion> output = new ArrayList<>();
        for (JsonNode value : node) {
            String id = value.path("speakerId").asText(null); if (id == null || !knownSpeakerIds.contains(id)) continue;
            SpeakerRole role; try { role = SpeakerRole.valueOf(value.path("role").asText("UNKNOWN")); } catch (IllegalArgumentException ignored) { role = SpeakerRole.UNKNOWN; }
            double confidence = value.path("confidence").asDouble(0); if (confidence < 0 || confidence > 1) continue;
            output.add(new RoleSuggestion(id, role, confidence));
        }
        return List.copyOf(output);
    }
    private static OrganizedBlockType parseUnitType(String raw) {
        if ("QA_PAIR".equals(raw)) return OrganizedBlockType.QA_PAIR;
        if ("NARRATIVE".equals(raw)) return OrganizedBlockType.NARRATIVE;
        throw invalid("Item type must be QA_PAIR or NARRATIVE");
    }
    private static List<String> idList(JsonNode node, Map<String, TranscriptSegment> source, String field) {
        if (!node.isArray() || node.isEmpty()) throw invalid(field + " must include source segment IDs");
        List<String> ids = new ArrayList<>(); for (JsonNode value : node) { String id = value.asText(null); if (id == null || !source.containsKey(id)) throw invalid(field + " cited an unknown source segment"); ids.add(id); }
        if (new HashSet<>(ids).size() != ids.size()) throw invalid(field + " repeated a source segment"); return List.copyOf(ids);
    }
    private static void ensureContiguous(List<String> ids, List<String> expected, int offset) {
        if (offset + ids.size() > expected.size()) throw invalid("Source segment IDs exceed transcript length");
        for (int index = 0; index < ids.size(); index++) if (!ids.get(index).equals(expected.get(offset + index))) throw invalid("Source segment IDs must remain in transcript order");
    }
    private static Span span(List<String> ids, Map<String, TranscriptSegment> source) {
        if (ids.isEmpty()) throw invalid("Source segment IDs cannot be empty");
        List<String> speakers = ids.stream().map(source::get).map(TranscriptSegment::getAsrSpeakerId).distinct().toList();
        return new Span(source.get(ids.get(0)).getStartMs(), source.get(ids.get(ids.size() - 1)).getEndMs(), speakers);
    }
    private String json(Object value) throws Exception { return mapper.writeValueAsString(value); }
    private String fragments(List<String> ids, Map<String, TranscriptSegment> source) throws Exception {
        List<Map<String, Object>> output = new ArrayList<>();
        for (String id : ids) { TranscriptSegment segment = source.get(id); output.add(Map.of("segmentId", id, "speakerId", segment.getAsrSpeakerId(), "startMs", segment.getStartMs(), "endMs", segment.getEndMs(), "text", segment.getTextContent())); }
        return mapper.writeValueAsString(output);
    }
    private static String bounded(String value, int limit, String label) { if (value == null || value.isBlank() || value.length() > limit) throw invalid(label + " is missing or too long"); return value.trim(); }
    private static String optionalBounded(String value, int limit) { if (value == null || value.isBlank()) return null; if (value.length() > limit) throw invalid("Text field is too long"); return value.trim(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static String clean(String text) { return text == null ? "" : text.replaceAll("\\s+", " ").trim(); }
    private static String titleFor(String filename) { int extension = filename.lastIndexOf('.'); return extension > 0 ? filename.substring(0, extension) : filename; }
    private static String plainText(String title, String summary, List<Topic> topics) {
        String body = topics.stream().map(topic -> "## " + topic.title() + (topic.summary() == null ? "" : "\n" + topic.summary()) + "\n" + topic.text()).collect(Collectors.joining("\n\n"));
        return "# " + title + (summary == null || summary.isBlank() ? "" : "\n" + summary) + (body.isBlank() ? "" : "\n\n" + body);
    }

    private static final class TurnBuilder {
        private final String speaker; private final long startMs; private long endMs; private final List<String> ids = new ArrayList<>(); private final StringBuilder text = new StringBuilder();
        private TurnBuilder(String speaker, TranscriptSegment segment, String content) { this.speaker = speaker; this.startMs = segment.getStartMs(); append(segment, content); }
        private void append(TranscriptSegment segment, String content) { if (!text.isEmpty()) text.append(' '); text.append(content); ids.add(segment.getId()); endMs = segment.getEndMs(); }
        private int characterCount() { return text.length(); }
        private Turn build() { return new Turn(speaker, startMs, endMs, List.copyOf(ids), text.toString()); }
    }
    private static final class TopicBuilder {
        private final long startMs; private long endMs; private final List<String> ids = new ArrayList<>(); private final List<ContentUnit> units = new ArrayList<>(); private final StringBuilder text = new StringBuilder(); private int characters;
        private TopicBuilder(Turn turn) { startMs = turn.startMs(); append(turn); }
        private void append(Turn turn) { if (!text.isEmpty()) text.append('\n'); String content = turn.speaker() + ": " + turn.text(); text.append(content); ids.addAll(turn.segmentIds()); endMs = turn.endMs(); characters += turn.text().length(); units.add(new ContentUnit(OrganizedBlockType.NARRATIVE, content, turn.startMs(), turn.endMs(), turn.segmentIds(), List.of(turn.speaker()))); }
        private int characterCount() { return characters; }
        private Topic build(int number) { return new Topic("主题 " + number, null, startMs, endMs, List.copyOf(ids), List.copyOf(units), text.toString()); }
    }

    public record OrganizationWork(OrganizedDocument document, List<TranscriptSegment> segments) { }
    public record ModelAction(boolean cached, String value) { static ModelAction cached(String response) { return new ModelAction(true, response); } static ModelAction call(String prompt) { return new ModelAction(false, prompt); } }
    public record Turn(String speaker, long startMs, long endMs, List<String> segmentIds, String text) { }
    public record ContentUnit(OrganizedBlockType type, String text, long startMs, long endMs, List<String> segmentIds, List<String> speakerIds) { }
    public record Topic(String title, String summary, long startMs, long endMs, List<String> segmentIds, List<ContentUnit> items, String text) {
        List<String> speakerIds() { return items.stream().flatMap(item -> item.speakerIds().stream()).distinct().toList(); }
    }
    public record RoleSuggestion(String speakerId, SpeakerRole role, Double confidence) { }
    public record OrganizationResult(String title, String summary, String mode, List<Turn> turns, List<Topic> topics, String plainText, List<RoleSuggestion> roleSuggestions) { }
    private record Span(long startMs, long endMs, List<String> speakerIds) { }
}
