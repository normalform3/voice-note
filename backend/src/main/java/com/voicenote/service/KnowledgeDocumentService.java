package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** MySQL is the source of truth for both the logical knowledge document and every rebuildable index generation. */
@Service
public class KnowledgeDocumentService {
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeIndexVersionRepository versions;
    private final KnowledgeIndexStageAttemptRepository stageAttempts;
    private final KnowledgeTopicRepository topics;
    private final KnowledgeChunkTopicRepository chunkTopics;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final PipelineProgressService pipeline;
    private final TranscriptionTaskRepository tasks;
    private final OrganizedDocumentBlockRepository organizedBlocks;
    private final OrganizedDocumentRepository organizedDocuments;
    private final KnowledgeChunker chunker;
    private final ProgressEventPublisher progressEvents;

    public KnowledgeDocumentService(KnowledgeDocumentRepository documents, KnowledgeChunkRepository chunks, KnowledgeIndexVersionRepository versions,
                                    KnowledgeIndexStageAttemptRepository stageAttempts, KnowledgeTopicRepository topics, KnowledgeChunkTopicRepository chunkTopics,
                                    OutboxService outbox, ObjectMapper mapper, AppProperties properties, PipelineProgressService pipeline,
                                    TranscriptionTaskRepository tasks, OrganizedDocumentBlockRepository organizedBlocks, OrganizedDocumentRepository organizedDocuments,
                                    KnowledgeChunker chunker, ProgressEventPublisher progressEvents) {
        this.documents = documents; this.chunks = chunks; this.versions = versions; this.stageAttempts = stageAttempts; this.topics = topics; this.chunkTopics = chunkTopics;
        this.outbox = outbox; this.mapper = mapper; this.properties = properties; this.pipeline = pipeline; this.tasks = tasks;
        this.organizedBlocks = organizedBlocks; this.organizedDocuments = organizedDocuments; this.chunker = chunker; this.progressEvents = progressEvents;
    }

    /** Legacy entrypoint retained for callers that still create raw-transcript knowledge documents. */
    @Transactional
    public KnowledgeDocument createForTranscript(TranscriptionTask task, AudioBlob audio, List<TranscriptSegment> segments) {
        return documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(task.getOwnerId(), task.getId(), task.getTranscriptVersion())
                .orElseGet(() -> documents.save(new KnowledgeDocument(task.getOwnerId(), task.getId(), task.getTranscriptVersion(), titleFor(audio.getOriginalFilename()))));
    }

    @Transactional
    public KnowledgeDocument createForOrganizedDocument(OrganizedDocument source, List<OrganizedDocumentBlock> ignored) {
        KnowledgeDocument document = documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(source.getOwnerId(), source.getTranscriptionTaskId(), source.getTranscriptVersion())
                .orElseGet(() -> documents.save(new KnowledgeDocument(source.getOwnerId(), source.getTranscriptionTaskId(), source.getTranscriptVersion(),
                        source.getTitle(), source.getId(), Math.toIntExact(source.getVersion()))));
        requestIndex(document, source, false);
        return document;
    }

    @Transactional
    public IndexBuildView rebuild(String ownerId, String documentId, boolean force) {
        KnowledgeDocument document = ownedDocument(ownerId, documentId);
        OrganizedDocument source = organizedDocuments.findById(document.getOrganizedDocumentId())
                .filter(value -> value.getOwnerId().equals(ownerId) && value.getStatus() == OrganizedDocumentStatus.READY)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "FORMAL_DOCUMENT_NOT_READY", "A ready formal document is required before rebuilding knowledge"));
        return indexBuildView(requestIndex(document, source, force));
    }

    private KnowledgeIndexVersion requestIndex(KnowledgeDocument document, OrganizedDocument source, boolean force) {
        String configurationHash = configurationHash();
        KnowledgeIndexVersion latest = versions.findTopByKnowledgeDocumentIdOrderByGenerationDesc(document.getId()).orElse(null);
        if (!force && latest != null && latest.getOrganizedDocumentVersion() == source.getVersion() && latest.getConfigurationHash().equals(configurationHash)
                && latest.getStatus() != KnowledgeIndexVersionStatus.FAILED && latest.getStatus() != KnowledgeIndexVersionStatus.RETIRED) return latest;
        int generation = latest == null ? 1 : latest.getGeneration() + 1;
        KnowledgeIndexVersion index = versions.save(new KnowledgeIndexVersion(document.getId(), generation, source.getId(), source.getVersion(), configurationHash));
        for (KnowledgeIndexStage stage : KnowledgeIndexStage.values()) stageAttempts.save(new KnowledgeIndexStageAttempt(index.getId(), stage, 1));
        outbox.enqueue("knowledge_index_version", index.getId(), EventType.KNOWLEDGE_INDEX_REQUESTED,
                "{\"knowledgeDocumentId\":\"" + document.getId() + "\",\"indexVersionId\":\"" + index.getId() + "\"}", "knowledge-index:" + index.getId());
        publish(document.getOwnerId(), index.getId());
        return index;
    }

    @Transactional public void markQueuedIndex(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); index.queue(); versions.save(index);
        documents.findById(index.getKnowledgeDocumentId()).ifPresent(document -> { if (!document.hasActiveIndexVersion()) { document.queue(); documents.save(document); } publish(document.getOwnerId(), index.getId()); });
    }
    @Transactional(readOnly = true) public List<String> queuedIndexVersionIds() { return versions.findTop10ByStatusOrderByUpdatedAtAsc(KnowledgeIndexVersionStatus.QUEUED).stream().map(KnowledgeIndexVersion::getId).toList(); }
    @Transactional(readOnly = true) public List<KnowledgeDocument> ownedDocuments(String ownerId) { return documents.findByOwnerIdOrderByUpdatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public KnowledgeDocument ownedDocument(String ownerId, String documentId) {
        return documents.findById(documentId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Knowledge document was not found"));
    }
    @Transactional(readOnly = true) public List<KnowledgeIndexVersion> ownedIndexVersions(String ownerId, String documentId) {
        ownedDocument(ownerId, documentId); return versions.findByKnowledgeDocumentIdOrderByGenerationDesc(documentId);
    }

    @Transactional
    public IndexWork claimIndex(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElse(null);
        if (index == null || !index.begin()) return null;
        KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        boolean bridgeTaskPipeline = tasks.findById(document.getTranscriptionTaskId())
                .map(task -> task.getCurrentStage() == PipelineStage.KNOWLEDGE_INDEX && task.getStatus() != TaskStatus.SUCCEEDED && task.getStatus() != TaskStatus.CANCELLED)
                .orElse(false);
        return new IndexWork(document, index, bridgeTaskPipeline);
    }

    @Transactional
    public List<KnowledgeTopic> ingestTopics(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow();
        KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        beginStage(index, KnowledgeIndexStage.INGEST);
        OrganizedDocument source = organizedDocuments.findById(index.getOrganizedDocumentId()).orElseThrow();
        if (source.getVersion() != index.getOrganizedDocumentVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "FORMAL_DOCUMENT_CHANGED", "Formal document changed before the index snapshot was created");
        }
        topics.deleteByKnowledgeIndexVersionId(indexVersionId);
        List<KnowledgeChunker.TopicSnapshot> snapshots = chunker.snapshotTopics(organizedBlocks.findByOrganizedDocumentIdOrderByBlockIndex(index.getOrganizedDocumentId()));
        List<KnowledgeTopic> stored = new ArrayList<>();
        try {
            for (KnowledgeChunker.TopicSnapshot snapshot : snapshots) {
                List<String> segmentIds = new ArrayList<>(); List<String> speakerIds = new ArrayList<>(); List<KnowledgeChunker.Fragment> fragments = new ArrayList<>();
                long start = Long.MAX_VALUE; long end = 0; StringBuilder text = new StringBuilder("## ").append(snapshot.title()).append('\n');
                for (KnowledgeChunker.UnitSnapshot unit : snapshot.units()) {
                    text.append(unit.text()).append('\n'); start = Math.min(start, unit.startMs()); end = Math.max(end, unit.endMs());
                    if (unit.sourceSegmentIds() != null) segmentIds.addAll(mapper.readValue(unit.sourceSegmentIds(), new TypeReference<List<String>>() { }));
                    if (unit.speakerIds() != null) speakerIds.addAll(mapper.readValue(unit.speakerIds(), new TypeReference<List<String>>() { }));
                    if (unit.sourceFragments() != null && !unit.sourceFragments().isBlank()) fragments.addAll(mapper.readValue(unit.sourceFragments(), new TypeReference<List<KnowledgeChunker.Fragment>>() { }));
                }
                if (start == Long.MAX_VALUE) { start = 0; end = 0; }
                stored.add(topics.save(new KnowledgeTopic(indexVersionId, snapshot.id(), snapshot.topicIndex(), snapshot.title(), text.toString(),
                        mapper.writeValueAsString(speakerIds.stream().filter(Objects::nonNull).distinct().toList()),
                        mapper.writeValueAsString(segmentIds.stream().distinct().toList()), mapper.writeValueAsString(fragments), mapper.writeValueAsString(snapshot.units()), start, end)));
            }
        } catch (Exception exception) { throw new IllegalStateException("Cannot persist knowledge topic snapshot", exception); }
        index.topicsCreated(stored.size()); versions.save(index); completeStage(index, KnowledgeIndexStage.INGEST, stored.size(), stored.size(), "{\"topicCount\":" + stored.size() + "}"); publish(document.getOwnerId(), indexVersionId);
        return stored;
    }

    @Transactional
    public List<KnowledgeChunk> createChunks(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        beginStage(index, KnowledgeIndexStage.CHUNK);
        List<KnowledgeChunker.EmbeddedChunk> drafts = chunker.buildFromTopics(document.getTitle(), topics.findByKnowledgeIndexVersionIdOrderByTopicIndex(indexVersionId));
        if (drafts.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_CHUNKS_EMPTY", "Formal document produced no semantic chunks");
        List<KnowledgeChunk> existing = chunks.findByKnowledgeIndexVersionIdOrderByChunkIndex(indexVersionId);
        if (!existing.isEmpty()) { chunkTopics.deleteByKnowledgeChunkIdIn(existing.stream().map(KnowledgeChunk::getId).toList()); chunks.deleteByKnowledgeIndexVersionId(indexVersionId); }
        List<KnowledgeChunk> stored = new ArrayList<>(); Map<String, Integer> topicChunkIndexes = new HashMap<>();
        try {
            for (int position = 0; position < drafts.size(); position++) {
                KnowledgeChunker.EmbeddedChunk draft = drafts.get(position); String content = draft.content();
                KnowledgeChunk chunk = chunks.save(new KnowledgeChunk(document.getId(), indexVersionId, position, draft.startMs(), draft.endMs(), mapper.writeValueAsString(draft.segmentIds()),
                        mapper.writeValueAsString(draft.blockIds()), draft.topicTitle(), mapper.writeValueAsString(draft.speakerIds()), mapper.writeValueAsString(draft.sourceFragments()),
                        mapper.writeValueAsString(draft.contextSegmentIds()), draft.tokenCount(), draft.oversized(), content, Hashing.sha256(content)));
                for (int topicOrder = 0; topicOrder < draft.topics().size(); topicOrder++) {
                    KnowledgeChunker.TopicReference topic = draft.topics().get(topicOrder);
                    int topicChunkIndex = topicChunkIndexes.merge(topic.id(), 1, Integer::sum) - 1;
                    chunkTopics.save(new KnowledgeChunkTopic(chunk.getId(), topic.id(), topicOrder, topicChunkIndex));
                }
                stored.add(chunk);
            }
        } catch (Exception exception) { throw new IllegalStateException("Cannot persist knowledge chunks", exception); }
        index.chunksCreated(stored.size()); versions.save(index); completeStage(index, KnowledgeIndexStage.CHUNK, stored.size(), stored.size(), "{\"chunkCount\":" + stored.size() + "}"); publish(document.getOwnerId(), indexVersionId);
        return stored;
    }

    @Transactional
    public List<KnowledgeChunk> beginIndexing(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); beginStage(index, KnowledgeIndexStage.INDEX);
        return chunks.findByKnowledgeIndexVersionIdOrderByChunkIndex(indexVersionId);
    }
    @Transactional(readOnly = true)
    public Map<String, List<String>> topicIdsForChunks(Collection<String> chunkIds) {
        Map<String, List<String>> output = new HashMap<>();
        for (KnowledgeChunkTopic link : chunkTopics.findByKnowledgeChunkIdIn(chunkIds)) {
            output.computeIfAbsent(link.getKnowledgeChunkId(), ignored -> new ArrayList<>()).add(link.getKnowledgeTopicId());
        }
        return output;
    }
    @Transactional
    public void indexedProgress(String indexVersionId, int completed, int total) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        index.indexed(completed); versions.save(index); KnowledgeIndexStageAttempt attempt = latestStage(indexVersionId, KnowledgeIndexStage.INDEX); attempt.progress(completed, total); stageAttempts.save(attempt); publish(document.getOwnerId(), indexVersionId);
    }
    @Transactional
    public ActivationResult activate(String indexVersionId) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        KnowledgeIndexVersion latest = versions.findTopByKnowledgeDocumentIdOrderByGenerationDesc(document.getId()).orElseThrow();
        if (!latest.getId().equals(indexVersionId)) {
            index.ready(); index.retire(); versions.save(index);
            completeStage(index, KnowledgeIndexStage.INDEX, index.getChunkCount(), index.getChunkCount(), "{\"indexedChunkCount\":" + index.getChunkCount() + "}"); publish(document.getOwnerId(), indexVersionId);
            return new ActivationResult(null, false);
        }
        String prior = document.getActiveIndexVersionId(); index.ready(); index.activate(); versions.save(index); document.activateIndexVersion(index.getId()); documents.save(document);
        if (prior != null && !prior.equals(index.getId())) versions.findById(prior).ifPresent(value -> { value.retire(); versions.save(value); });
        completeStage(index, KnowledgeIndexStage.INDEX, index.getChunkCount(), index.getChunkCount(), "{\"indexedChunkCount\":" + index.getChunkCount() + "}"); publish(document.getOwnerId(), indexVersionId);
        return new ActivationResult(prior, true);
    }
    @Transactional
    public void failIndex(String indexVersionId, String code, String message) {
        KnowledgeIndexVersion index = versions.findById(indexVersionId).orElseThrow(); KnowledgeDocument document = documents.findById(index.getKnowledgeDocumentId()).orElseThrow();
        index.fail(code + ": " + message); versions.save(index); KnowledgeIndexStage stage = index.getCurrentStage() == null ? KnowledgeIndexStage.INGEST : index.getCurrentStage();
        KnowledgeIndexStageAttempt attempt = latestStage(indexVersionId, stage); attempt.fail(code, message); stageAttempts.save(attempt);
        if (!document.hasActiveIndexVersion()) { document.fail(index.getFailureMessage()); documents.save(document); }
        publish(document.getOwnerId(), indexVersionId);
    }

    @Transactional
    public void retry(String ownerId, String documentId) {
        KnowledgeDocument document = ownedDocument(ownerId, documentId);
        tasks.findById(document.getTranscriptionTaskId())
                .filter(task -> task.getCurrentStage() == PipelineStage.KNOWLEDGE_INDEX
                        && task.getStatus() != TaskStatus.SUCCEEDED
                        && task.getStatus() != TaskStatus.CANCELLED)
                .ifPresent(task -> pipeline.retryStage(ownerId, task.getId(), PipelineStage.KNOWLEDGE_INDEX));
        rebuild(ownerId, documentId, true);
    }
    @Transactional public void retryForTask(String ownerId, String taskId) {
        KnowledgeDocument document = documents.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(ownerId, taskId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Knowledge document was not found"));
        retry(ownerId, document.getId());
    }

    @Transactional(readOnly = true)
    public IndexBuildView currentBuild(String documentId) {
        return versions.findTopByKnowledgeDocumentIdOrderByGenerationDesc(documentId).map(this::indexBuildView).orElse(null);
    }
    @Transactional(readOnly = true) public IndexBuildView indexBuildView(KnowledgeIndexVersion index) {
        List<IndexStageView> stages = stageAttempts.findByKnowledgeIndexVersionIdOrderByQueuedAtAsc(index.getId()).stream().map(IndexStageView::from).toList();
        int progress = stages.isEmpty() ? 0 : stages.stream().mapToInt(value -> switch (value.stage()) { case "INGEST" -> value.progressPercent() * 15 / 100; case "CHUNK" -> 15 + value.progressPercent() * 25 / 100; case "INDEX" -> 40 + value.progressPercent() * 60 / 100; default -> 0; }).max().orElse(0);
        if (index.getStatus() == KnowledgeIndexVersionStatus.READY) progress = 100;
        return new IndexBuildView(index.getId(), index.getGeneration(), index.getStatus().name(), index.getCurrentStage() == null ? null : index.getCurrentStage().name(), progress,
                index.getTopicCount(), index.getChunkCount(), index.getIndexedChunkCount(), index.getFailureMessage(), index.isActive(), stages);
    }

    private void beginStage(KnowledgeIndexVersion index, KnowledgeIndexStage stage) {
        KnowledgeIndexStageAttempt attempt = latestStage(index.getId(), stage); if (!attempt.start()) throw new IllegalStateException("Knowledge index stage is not ready: " + stage);
        index.stage(stage); versions.save(index); stageAttempts.save(attempt);
    }
    private void completeStage(KnowledgeIndexVersion index, KnowledgeIndexStage stage, int completed, int total, String snapshot) {
        KnowledgeIndexStageAttempt attempt = latestStage(index.getId(), stage); attempt.progress(completed, total); attempt.succeed(snapshot); stageAttempts.save(attempt);
    }
    private KnowledgeIndexStageAttempt latestStage(String indexVersionId, KnowledgeIndexStage stage) { return stageAttempts.findTopByKnowledgeIndexVersionIdAndStageOrderByAttemptNumberDesc(indexVersionId, stage).orElseThrow(); }
    private String configurationHash() {
        return Hashing.canonicalJsonHash(Map.of("chunker", "topic-v2", "shortTopicTokens", properties.getKnowledge().getShortTopicTokens(), "targetTokens", properties.getKnowledge().getChunkTargetTokens(),
                "maxTokens", properties.getKnowledge().getChunkMaxTokens(), "embeddingModel", String.valueOf(properties.getDashscope().getEmbeddingModel()), "embeddingDimension", properties.getDashscope().getEmbeddingDimension()));
    }
    private void publish(String ownerId, String indexVersionId) { progressEvents.publish(new ProgressEventPublisher.ProgressNotification(ownerId, "knowledge-index-progress", indexVersionId)); }
    private String titleFor(String filename) { int extension = filename.lastIndexOf('.'); return extension > 0 ? filename.substring(0, extension) : filename; }

    /** Kept only for legacy raw-transcript tests and import compatibility; formal-document indexing uses KnowledgeChunker. */
    static List<ChunkDraft> chunk(List<TranscriptSegment> source, int maximumCharacters) {
        int limit = Math.max(maximumCharacters, 400); List<ChunkDraft> output = new ArrayList<>();
        StringBuilder content = new StringBuilder(); List<String> ids = new ArrayList<>(); long start = 0; long end = 0;
        for (TranscriptSegment segment : source) {
            String speaker = segment.getSpeakerLabel() == null || segment.getSpeakerLabel().isBlank() ? "原声" : segment.getSpeakerLabel();
            String line = "[" + segment.getId() + "] " + speaker + " " + segment.getStartMs() + "-" + segment.getEndMs() + "ms: " + segment.getTextContent() + "\n";
            if (!content.isEmpty() && content.length() + line.length() > limit) { output.add(new ChunkDraft(start, end, List.copyOf(ids), content.toString())); content = new StringBuilder(); ids = new ArrayList<>(); }
            if (content.isEmpty()) start = segment.getStartMs(); content.append(line); ids.add(segment.getId()); end = segment.getEndMs();
        }
        if (!content.isEmpty()) output.add(new ChunkDraft(start, end, List.copyOf(ids), content.toString())); return output;
    }

    public record IndexWork(KnowledgeDocument document, KnowledgeIndexVersion indexVersion, boolean bridgeTaskPipeline) { }
    public record ActivationResult(String previousIndexVersionId, boolean activated) { }
    record ChunkDraft(long startMs, long endMs, List<String> segmentIds, String content) { }
    public record IndexStageView(String stage, String status, int attemptNumber, int progressPercent, int completedCount, int totalCount,
                                 java.time.Instant queuedAt, java.time.Instant startedAt, java.time.Instant completedAt, java.time.Instant nextRetryAt, String errorCode, String errorMessage) {
        static IndexStageView from(KnowledgeIndexStageAttempt value) { return new IndexStageView(value.getStage().name(), value.getStatus().name(), value.getAttemptNumber(), value.getProgressPercent(), value.getCompletedCount(), value.getTotalCount(), value.getQueuedAt(), value.getStartedAt(), value.getCompletedAt(), value.getNextRetryAt(), value.getErrorCode(), value.getErrorMessage()); }
    }
    public record IndexBuildView(String id, int generation, String status, String currentStage, int progressPercent, int topicCount, int chunkCount,
                                 int indexedChunkCount, String failureMessage, boolean active, List<IndexStageView> stages) { }
}
