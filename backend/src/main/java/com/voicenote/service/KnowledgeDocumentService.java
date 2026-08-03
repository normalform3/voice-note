package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.KnowledgeChunkRepository;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeDocumentService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;

    public KnowledgeDocumentService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks, OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this.documents = documents; this.chunks = chunks; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
    }

    @Transactional
    public KnowledgeDocument createForTranscript(TranscriptionTask task, AudioBlob audio, List<TranscriptSegment> segments) {
        return documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .orElseGet(() -> {
                    KnowledgeDocument document = documents.save(new KnowledgeDocument(task.getOwnerId(), task.getId(), task.getTranscriptVersion(), titleFor(audio.getOriginalFilename())));
                    List<ChunkDraft> drafts = chunk(segments, properties.getKnowledge().getChunkCharacters());
                    for (int index = 0; index < drafts.size(); index++) {
                        ChunkDraft draft = drafts.get(index);
                        try {
                            chunks.save(new KnowledgeChunk(document.getId(), index, draft.startMs(), draft.endMs(), mapper.writeValueAsString(draft.segmentIds()), draft.content(), Hashing.sha256(draft.content())));
                        } catch (Exception exception) { throw new IllegalStateException("Cannot persist knowledge chunk", exception); }
                    }
                    outbox.enqueue("knowledge_document", document.getId(), EventType.KNOWLEDGE_INDEX_REQUESTED);
                    return document;
                });
    }

    @Transactional public void markQueued(String documentId) { documents.findById(documentId).orElseThrow().queue(); }
    @Transactional(readOnly = true) public List<KnowledgeDocument> ownedDocuments(String ownerId) { return documents.findByOwnerIdOrderByUpdatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public KnowledgeDocument ownedDocument(String ownerId, String documentId) {
        return documents.findById(documentId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Knowledge document was not found"));
    }
    @Transactional(readOnly = true) public List<String> queuedDocumentIds() { return documents.findTop10ByStatusOrderByCreatedAtAsc(KnowledgeDocumentStatus.QUEUED).stream().map(KnowledgeDocument::getId).toList(); }
    @Transactional public IndexWork claim(String documentId) {
        KnowledgeDocument document = documents.findById(documentId).orElse(null);
        if (document == null || !document.beginIndexing()) return null;
        return new IndexWork(document, chunks.findByKnowledgeDocumentIdOrderByChunkIndex(documentId));
    }
    @Transactional public void complete(String documentId) { KnowledgeDocument document = documents.findById(documentId).orElseThrow(); document.ready(); documents.save(document); }
    @Transactional public void fail(String documentId, String message) { KnowledgeDocument document = documents.findById(documentId).orElseThrow(); document.fail(message); documents.save(document); }
    @Transactional public void retry(String ownerId, String documentId) {
        KnowledgeDocument document = ownedDocument(ownerId, documentId);
        if (!document.retry()) throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_RETRYABLE", "Only failed knowledge documents can be retried");
        documents.save(document); outbox.enqueue("knowledge_document", document.getId(), EventType.KNOWLEDGE_INDEX_REQUESTED);
    }

    static List<ChunkDraft> chunk(List<TranscriptSegment> source, int maximumCharacters) {
        int limit = Math.max(maximumCharacters, 400);
        List<ChunkDraft> output = new ArrayList<>();
        StringBuilder content = new StringBuilder(); List<String> ids = new ArrayList<>(); long start = 0; long end = 0;
        for (TranscriptSegment segment : source) {
            String speaker = segment.getSpeakerLabel() == null || segment.getSpeakerLabel().isBlank() ? "原声" : segment.getSpeakerLabel();
            String line = "[" + segment.getId() + "] " + speaker + " " + segment.getStartMs() + "-" + segment.getEndMs() + "ms: " + segment.getTextContent() + "\n";
            if (!content.isEmpty() && content.length() + line.length() > limit) {
                output.add(new ChunkDraft(start, end, List.copyOf(ids), content.toString())); content = new StringBuilder(); ids = new ArrayList<>();
            }
            if (content.isEmpty()) start = segment.getStartMs();
            content.append(line); ids.add(segment.getId()); end = segment.getEndMs();
        }
        if (!content.isEmpty()) output.add(new ChunkDraft(start, end, List.copyOf(ids), content.toString()));
        return output;
    }

    private String titleFor(String filename) {
        int extension = filename.lastIndexOf('.');
        return extension > 0 ? filename.substring(0, extension) : filename;
    }

    public record IndexWork(KnowledgeDocument document, List<KnowledgeChunk> chunks) { }
    record ChunkDraft(long startMs, long endMs, List<String> segmentIds, String content) { }
}
