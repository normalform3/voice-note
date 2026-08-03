package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.KnowledgeRunEvidenceRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class KnowledgeAgentService {
    private static final String CREATE_OPERATION = "CREATE_KNOWLEDGE_RUN";
    private static final int MAX_TOOL_CALLS = 4;
    private final KnowledgeRunRepository runs;
    private final KnowledgeRunEvidenceRepository evidence;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ProgressEventPublisher progressEvents;

    public KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence, IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties, ProgressEventPublisher progressEvents) {
        this.runs = runs; this.evidence = evidence; this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties; this.progressEvents = progressEvents;
    }
    KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence, IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this(runs, evidence, idempotency, outbox, mapper, properties, new ProgressEventPublisher(event -> { }));
    }

    @Transactional
    public KnowledgeRun create(String ownerId, String key, String question) {
        String normalized = question.trim();
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, Hashing.canonicalJsonHash(Map.of("question", normalized)));
        if (record.getResourceId() != null) return ownedRun(ownerId, record.getResourceId());
        KnowledgeRun run = runs.save(new KnowledgeRun(ownerId, normalized, properties.getDashscope().getChatModel(), MAX_TOOL_CALLS));
        outbox.enqueue("knowledge_run", run.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);
        try { idempotency.complete(record, run.getId(), mapper.writeValueAsString(KnowledgeRunView.from(run))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent knowledge response", exception); }
        return run;
    }

    @Transactional public void markQueued(String runId) { runs.findById(runId).orElseThrow().queue(); }
    @Transactional(readOnly = true) public List<KnowledgeRun> ownedRuns(String ownerId) { return runs.findByOwnerIdOrderByCreatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public KnowledgeRun ownedRun(String ownerId, String runId) {
        return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KNOWLEDGE_RUN_NOT_FOUND", "Knowledge task was not found"));
    }
    @Transactional(readOnly = true) public List<String> queuedRunIds() { return runs.findTop10ByStatusOrderByCreatedAtAsc(KnowledgeRunStatus.QUEUED).stream().map(KnowledgeRun::getId).toList(); }
    @Transactional public RunWork claim(String runId) {
        KnowledgeRun run = runs.findById(runId).orElse(null); if (run == null || !run.start()) return null;
        return new RunWork(run.getId(), run.getOwnerId(), run.getQuestion());
    }
    @Transactional public void consumeTool(String runId) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        if (!run.consumeTool()) { runs.save(run); throw new ApiException(HttpStatus.CONFLICT, "AGENT_TOOL_BUDGET_EXHAUSTED", "Knowledge agent tool budget exhausted"); }
        runs.save(run);
    }
    @Transactional public void fail(String runId, String message) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.fail(message); runs.save(run); notifySettled(run); }

    @Transactional
    public void complete(String runId, String rawResult, Collection<KnowledgeSearchService.ReadableChunk> readableChunks) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        try {
            JsonNode parsed = mapper.readTree(rawResult);
            if (!parsed.isObject() || !parsed.path("answer").isTextual() || !parsed.path("findings").isArray()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_RESULT_INVALID", "Knowledge agent must return answer and findings JSON");
            }
            Map<String, KnowledgeSearchService.ReadableChunk> allowed = new HashMap<>();
            for (KnowledgeSearchService.ReadableChunk chunk : readableChunks) allowed.put(chunk.chunkId(), chunk);
            int citations = 0; Set<String> persistedEvidence = new HashSet<>();
            for (int findingIndex = 0; findingIndex < parsed.path("findings").size(); findingIndex++) {
                JsonNode finding = parsed.path("findings").get(findingIndex);
                if (!finding.path("evidence").isArray()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_RESULT_INVALID", "Each finding must include evidence");
                for (JsonNode citation : finding.path("evidence")) {
                    String chunkId = citation.path("chunkId").asText(null); String segmentId = citation.path("segmentId").asText(null);
                    KnowledgeSearchService.ReadableChunk chunk = allowed.get(chunkId);
                    if (chunk == null || segmentId == null || !chunk.segmentIds().contains(segmentId)) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EVIDENCE", "Knowledge agent cited a segment it did not read");
                    }
                    String resultPath = "/findings/" + findingIndex;
                    if (persistedEvidence.add(resultPath + ":" + segmentId)) {
                        evidence.save(new KnowledgeRunEvidence(run.getId(), chunk.documentId(), chunk.chunkId(), resultPath, segmentId)); citations++;
                    }
                }
            }
            if (!allowed.isEmpty() && citations == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_EVIDENCE", "Knowledge answer must cite source transcript segments");
            run.succeed(mapper.writeValueAsString(parsed)); runs.save(run); notifySettled(run);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_RESULT_INVALID", "Knowledge agent did not return valid JSON"); }
    }

    public record RunWork(String runId, String ownerId, String question) { }
    private void notifySettled(KnowledgeRun run) {
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("knowledge_run", run.getId(), EventType.PROGRESS_CHANGED);
        else progressEvents.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "knowledge-run-settled", run.getId()));
    }
    public record KnowledgeRunView(String id, KnowledgeRunStatus status, int toolCallsUsed, int maxToolCalls, String resultDocument, String failureMessage) {
        public static KnowledgeRunView from(KnowledgeRun run) { return new KnowledgeRunView(run.getId(), run.getStatus(), run.getToolCallsUsed(), run.getMaxToolCalls(), run.getResultDocument(), run.getFailureMessage()); }
    }
}
