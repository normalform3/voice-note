package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a deterministic, evidence-preserving reading document from immutable ASR segments. */
@Service
public class DocumentOrganizationService {
    private final OrganizedDocumentRepository documents;
    private final OrganizedDocumentBlockRepository blocks;
    private final TranscriptSegmentRepository segments;
    private final TranscriptionTaskRepository tasks;
    private final OutboxService outbox;
    private final ObjectMapper mapper;

    public DocumentOrganizationService(OrganizedDocumentRepository documents, OrganizedDocumentBlockRepository blocks,
                                       TranscriptSegmentRepository segments, TranscriptionTaskRepository tasks,
                                       OutboxService outbox, ObjectMapper mapper) {
        this.documents = documents; this.blocks = blocks; this.segments = segments; this.tasks = tasks;
        this.outbox = outbox; this.mapper = mapper;
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
    public List<OrganizedDocumentBlock> complete(String documentId, OrganizationResult result) {
        OrganizedDocument document = documents.findById(documentId).orElseThrow();
        TranscriptionTask task = tasks.findById(document.getTranscriptionTaskId()).orElseThrow();
        if (task.isCancelled()) return List.of();
        try {
            blocks.deleteByOrganizedDocumentId(documentId);
            List<OrganizedDocumentBlock> stored = new ArrayList<>();
            for (int index = 0; index < result.topics().size(); index++) {
                Topic topic = result.topics().get(index);
                stored.add(blocks.save(new OrganizedDocumentBlock(documentId, index, OrganizedBlockType.TOPIC, null, topic.title(),
                        topic.startMs(), topic.endMs(), mapper.writeValueAsString(topic.segmentIds()), topic.text())));
            }
            document.ready(mapper.writeValueAsString(Map.of("turns", result.turns(), "topics", result.topics())), result.plainText());
            documents.save(document);
            return stored;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot persist organized document", exception);
        }
    }

    @Transactional public void fail(String documentId, String message) { documents.findById(documentId).ifPresent(document -> { document.fail(message); documents.save(document); }); }
    @Transactional(readOnly = true) public OrganizedDocument ownedDocument(String ownerId, String documentId) {
        return documents.findById(documentId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZED_DOCUMENT_NOT_FOUND", "Organized document was not found"));
    }
    @Transactional(readOnly = true) public List<OrganizedDocumentBlock> ownedBlocks(String ownerId, String documentId) {
        ownedDocument(ownerId, documentId); return blocks.findByOrganizedDocumentIdOrderByBlockIndex(documentId);
    }
    @Transactional
    public void retryForTask(String ownerId, String taskId) {
        OrganizedDocument document = documents.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(ownerId, taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZED_DOCUMENT_NOT_FOUND", "Organized document was not found"));
        document.queue(); documents.save(document);
        outbox.enqueue("organized_document", document.getId(), EventType.DOCUMENT_ORGANIZATION_REQUESTED,
                "{\"taskId\":\"" + taskId + "\",\"stage\":\"DOCUMENT_ORGANIZATION\",\"documentId\":\"" + document.getId() + "\"}",
                "task:" + taskId + ":document:" + document.getId() + ":retry:" + System.nanoTime());
    }
    @Transactional
    public String recoverForTask(String taskId) {
        return documents.findTopByTranscriptionTaskIdOrderByUpdatedAtDesc(taskId)
                .filter(document -> document.recover())
                .map(document -> { documents.save(document); return document.getId(); })
                .orElse(null);
    }

    static OrganizationResult organize(List<TranscriptSegment> source) {
        List<Turn> turns = new ArrayList<>();
        TurnBuilder current = null;
        for (TranscriptSegment segment : source) {
            String text = clean(segment.getTextContent());
            if (text.isBlank()) continue;
            String speaker = segment.getSpeakerLabel() == null || segment.getSpeakerLabel().isBlank() ? "SPEAKER_UNKNOWN" : segment.getSpeakerLabel();
            if (current != null && current.speaker.equals(speaker) && segment.getStartMs() - current.endMs <= 5_000) {
                current.append(segment, text);
            } else {
                if (current != null) turns.add(current.build());
                current = new TurnBuilder(speaker, segment, text);
            }
        }
        if (current != null) turns.add(current.build());

        List<Topic> topics = new ArrayList<>();
        TopicBuilder topic = null;
        for (Turn turn : turns) {
            if (topic == null || turn.startMs() - topic.endMs > 30_000 || topic.characterCount() + turn.text().length() > 2_400) {
                if (topic != null) topics.add(topic.build(topics.size() + 1));
                topic = new TopicBuilder(turn);
            } else {
                topic.append(turn);
            }
        }
        if (topic != null) topics.add(topic.build(topics.size() + 1));
        String plainText = topics.stream().map(value -> "## " + value.title() + "\n" + value.text()).reduce("", (left, right) -> left.isEmpty() ? right : left + "\n\n" + right);
        return new OrganizationResult(List.copyOf(turns), List.copyOf(topics), plainText);
    }

    private static String clean(String text) { return text == null ? "" : text.replaceAll("\\s+", " ").trim(); }
    private static String titleFor(String filename) { int extension = filename.lastIndexOf('.'); return extension > 0 ? filename.substring(0, extension) : filename; }

    private static final class TurnBuilder {
        private final String speaker; private final long startMs; private long endMs; private final List<String> ids = new ArrayList<>(); private final StringBuilder text = new StringBuilder();
        private TurnBuilder(String speaker, TranscriptSegment segment, String content) { this.speaker = speaker; this.startMs = segment.getStartMs(); append(segment, content); }
        private void append(TranscriptSegment segment, String content) { if (!text.isEmpty()) text.append(' '); text.append(content); ids.add(segment.getId()); endMs = segment.getEndMs(); }
        private Turn build() { return new Turn(speaker, startMs, endMs, List.copyOf(ids), text.toString()); }
    }
    private static final class TopicBuilder {
        private final long startMs; private long endMs; private final List<String> ids = new ArrayList<>(); private final StringBuilder text = new StringBuilder(); private int characters;
        private TopicBuilder(Turn turn) { startMs = turn.startMs(); append(turn); }
        private void append(Turn turn) { if (!text.isEmpty()) text.append('\n'); text.append(turn.speaker()).append(": ").append(turn.text()); ids.addAll(turn.segmentIds()); endMs = turn.endMs(); characters += turn.text().length(); }
        private int characterCount() { return characters; }
        private Topic build(int number) { return new Topic("主题 " + number, startMs, endMs, List.copyOf(ids), text.toString()); }
    }

    public record OrganizationWork(OrganizedDocument document, List<TranscriptSegment> segments) { }
    public record Turn(String speaker, long startMs, long endMs, List<String> segmentIds, String text) { }
    public record Topic(String title, long startMs, long endMs, List<String> segmentIds, String text) { }
    public record OrganizationResult(List<Turn> turns, List<Topic> topics, String plainText) { }
}
