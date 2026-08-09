package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.*;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class KnowledgeAgentService {
    private static final String CREATE_OPERATION = "CREATE_KNOWLEDGE_RUN";
    private static final String CREATE_AGENT_OPERATION = "CREATE_AGENT_RUN";
    private static final int LEGACY_MAX_TOOL_CALLS = 4;
    private final KnowledgeRunRepository runs;
    private final KnowledgeRunEvidenceRepository evidence;
    private final KnowledgeRunDocumentRepository runDocuments;
    private final KnowledgeRunStepRepository steps;
    private final KnowledgeRunSourceRepository sources;
    private final TranscriptionTaskRepository tasks;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final TranscriptSegmentRepository segments;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ProgressEventPublisher progressEvents;
    private final AgentSkillRegistry skills;
    private final AgentMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence,
                                 KnowledgeRunDocumentRepository runDocuments, KnowledgeRunStepRepository steps,
                                 KnowledgeRunSourceRepository sources, TranscriptionTaskRepository tasks, KnowledgeDocumentRepository documents,
                                 KnowledgeChunkRepository chunks, TranscriptSegmentRepository segments,
                                 IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties,
                                 ProgressEventPublisher progressEvents, AgentSkillRegistry skills, AgentMetrics metrics) {
        this.runs = runs; this.evidence = evidence; this.runDocuments = runDocuments; this.steps = steps; this.sources = sources;
        this.tasks = tasks; this.documents = documents; this.chunks = chunks; this.segments = segments;
        this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
        this.progressEvents = progressEvents; this.skills = skills; this.metrics = metrics;
    }

    /** Compatibility constructor used by focused legacy evidence tests. */
    KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence, IdempotencyService idempotency,
                          OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this(runs, evidence, null, null, null, null, null, null, null, idempotency, outbox, mapper, properties,
                new ProgressEventPublisher(event -> { }), null, null);
    }

    /** Legacy /knowledge-runs creation remains owner-wide and uses the previous fixed worker while the agent feature flag is off. */
    @Transactional
    public KnowledgeRun create(String ownerId, String key, String question) {
        if (properties.getAgent().isEnabled()) {
            return createAgent(ownerId, key, new CreateAgentCommand(question,
                    new AgentScopeCommand(AgentScopeType.ALL_DOCUMENTS, List.of()), null, "Asia/Shanghai"));
        }
        String normalized = question.trim();
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, Hashing.canonicalJsonHash(Map.of("question", normalized)));
        if (record.getResourceId() != null) return ownedRun(ownerId, record.getResourceId());
        KnowledgeRun run = runs.save(new KnowledgeRun(ownerId, normalized, properties.getDashscope().getChatModel(), LEGACY_MAX_TOOL_CALLS));
        outbox.enqueue("knowledge_run", run.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);
        completeIdempotency(record, run, KnowledgeRunView.from(run)); return run;
    }

    @Transactional
    public KnowledgeRun createAgent(String ownerId, String key, CreateAgentCommand command) {
        if (!properties.getAgent().isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_DISABLED", "The bounded Agent runtime is disabled");
        String question = command.question() == null ? "" : command.question().trim();
        if (question.isBlank() || question.length() > 8_000) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_QUESTION", "question must contain 1 to 8000 characters");
        ZoneId zone;
        try { zone = ZoneId.of(command.timeZone() == null || command.timeZone().isBlank() ? "Asia/Shanghai" : command.timeZone()); }
        catch (DateTimeException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_ZONE", "timeZone must be a valid IANA zone"); }
        AgentScopeCommand scope = command.scope() == null ? null : command.scope();
        if (scope == null || scope.type() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SCOPE_REQUIRED", "scope.type is required");
        List<ResolvedDocument> resolved = resolveScope(ownerId, scope);
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_AGENT_OPERATION, key, Hashing.canonicalJsonHash(command));
        if (record.getResourceId() != null) return ownedRun(ownerId, record.getResourceId());
        AgentSkill selected = command.skillId() == null || command.skillId().isBlank() ? skills.fallback() : requireSkill(command.skillId());
        boolean auto = command.skillId() == null || command.skillId().isBlank();
        String snapshot = json(selected); String hash = Hashing.sha256(snapshot);
        KnowledgeRun run = runs.save(new KnowledgeRun(ownerId, question, properties.getDashscope().getChatModel(), scope.type(), zone.getId(),
                auto ? "auto" : selected.id(), auto ? "pending" : selected.version(), snapshot, hash,
                properties.getAgent().getMaxModelCalls(), properties.getAgent().getMaxTurns(), properties.getAgent().getMaxToolCalls()));
        for (ResolvedDocument document : resolved) runDocuments.save(new KnowledgeRunDocument(run.getId(), document.task().getId(),
                document.document() == null ? null : document.document().getId(), document.document() == null ? null : document.document().getActiveIndexVersionId(), metadata(document)));
        outbox.enqueue("knowledge_run", run.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);
        completeIdempotency(record, run, AgentRunView.from(run, resolved.size())); return run;
    }

    private List<ResolvedDocument> resolveScope(String ownerId, AgentScopeCommand scope) {
        LinkedHashSet<String> requested = new LinkedHashSet<>(scope.transcriptionTaskIds() == null ? List.of() : scope.transcriptionTaskIds());
        List<ResolvedDocument> resolved = new ArrayList<>();
        if (scope.type() == AgentScopeType.CURRENT_DOCUMENT) {
            if (requested.size() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_DOCUMENT_REQUIRED", "CURRENT_DOCUMENT requires exactly one transcriptionTaskId");
            TranscriptionTask task = ownedTask(ownerId, requested.iterator().next());
            if (!task.isTranscriptReady()) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Current document questions require a persisted transcript");
            KnowledgeDocument document = documents.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(ownerId, task.getId()).orElse(null);
            if (document != null && (document.getStatus() != KnowledgeDocumentStatus.READY || document.getActiveIndexVersionId() == null
                    || document.getTranscriptVersion() != task.getTranscriptVersion())) document = null;
            resolved.add(new ResolvedDocument(task, document));
        } else if (scope.type() == AgentScopeType.SELECTED_DOCUMENTS) {
            if (requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "SELECTED_DOCUMENTS_REQUIRED", "SELECTED_DOCUMENTS requires at least one transcriptionTaskId");
            for (String taskId : requested) resolved.add(indexed(ownerId, taskId));
        } else {
            if (!requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "ALL_DOCUMENTS_HAS_IDS", "ALL_DOCUMENTS must not include transcriptionTaskIds");
            LinkedHashSet<String> seenTasks = new LinkedHashSet<>();
            for (KnowledgeDocument document : documents.findByOwnerIdOrderByUpdatedAtDesc(ownerId)) {
                if (document.getStatus() != KnowledgeDocumentStatus.READY || document.getActiveIndexVersionId() == null) continue;
                if (!seenTasks.add(document.getTranscriptionTaskId())) continue;
                resolved.add(new ResolvedDocument(ownedTask(ownerId, document.getTranscriptionTaskId()), document));
            }
        }
        if (resolved.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "AGENT_SCOPE_EMPTY", "No ready documents are available in this scope");
        if (resolved.size() > properties.getAgent().getMaxScopeDocuments()) throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SCOPE_TOO_LARGE", "Agent scope exceeds the configured document limit");
        return List.copyOf(resolved);
    }

    private ResolvedDocument indexed(String ownerId, String taskId) {
        TranscriptionTask task = ownedTask(ownerId, taskId);
        KnowledgeDocument document = documents.findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(ownerId, taskId)
                .filter(value -> value.getStatus() == KnowledgeDocumentStatus.READY && value.getActiveIndexVersionId() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_SEARCHABLE", "Selected documents must have an active knowledge index"));
        return new ResolvedDocument(task, document);
    }

    private TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }

    private String metadata(ResolvedDocument value) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("title", value.document() == null ? Objects.toString(value.task().getSubject(), "录音 " + value.task().getId().substring(0, 8)) : value.document().getTitle());
            snapshot.put("occurredAt", value.task().getOccurredAt()); snapshot.put("sceneType", value.task().getSceneType().name());
            snapshot.put("subject", value.task().getSubject()); snapshot.put("tags", mapper.readTree(value.task().getTags() == null ? "[]" : value.task().getTags()));
            snapshot.put("transcriptVersion", value.document() == null ? value.task().getTranscriptVersion() : value.document().getTranscriptVersion());
            return mapper.writeValueAsString(snapshot);
        } catch (Exception exception) { throw new IllegalStateException("Cannot snapshot Agent document metadata", exception); }
    }

    @Transactional public void markQueued(String runId) { runs.findById(runId).orElseThrow().queue(); }
    @Transactional(readOnly = true) public List<KnowledgeRun> ownedRuns(String ownerId) { return runs.findByOwnerIdOrderByCreatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public KnowledgeRun ownedRun(String ownerId, String runId) {
        return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KNOWLEDGE_RUN_NOT_FOUND", "Knowledge task was not found"));
    }
    @Transactional(readOnly = true) public List<KnowledgeRunDocument> runDocuments(String runId) { return runDocuments.findByKnowledgeRunIdOrderByCreatedAtAsc(runId); }
    @Transactional(readOnly = true) public List<KnowledgeRunStep> runSteps(String runId) { return steps.findByKnowledgeRunIdOrderByStepIndexAsc(runId); }
    @Transactional(readOnly = true) public List<String> queuedRunIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(runs.findTop10ByStatusOrderByCreatedAtAsc(KnowledgeRunStatus.QUEUED).stream().map(KnowledgeRun::getId).toList());
        ids.addAll(runs.findTop10ByStatusAndLeaseUntilBeforeOrderByCreatedAtAsc(KnowledgeRunStatus.RUNNING, Instant.now()).stream().map(KnowledgeRun::getId).toList());
        return List.copyOf(ids);
    }
    @Transactional public RunWork claim(String runId) {
        KnowledgeRun run = runs.findById(runId).orElse(null); if (run == null || !run.start()) return null; runs.save(run);
        return new RunWork(run.getId(), run.getOwnerId(), run.getQuestion(), run.isLegacy());
    }
    @Transactional public void selectSkill(String runId, AgentSkill skill) {
        KnowledgeRun run = runs.findById(runId).orElseThrow(); String snapshot = json(skill);
        run.selectSkill(skill.id(), skill.version(), snapshot, Hashing.sha256(snapshot)); runs.save(run);
    }
    @Transactional public boolean consumeModel(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); boolean value = run.consumeModelCall(); runs.save(run); return value; }
    @Transactional public boolean consumeTurn(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); boolean value = run.consumeTurn(); runs.save(run); return value; }
    @Transactional public void consumeTool(String runId) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        if (!run.consumeTool()) { runs.save(run); throw new ApiException(HttpStatus.CONFLICT, "AGENT_TOOL_BUDGET_EXHAUSTED", "Knowledge agent tool budget exhausted"); }
        run.renewLease(); runs.save(run);
    }
    @Transactional public String beginStep(String runId, AgentStepType type, String callId, String toolName, String input) {
        int index = Math.toIntExact(steps.countByKnowledgeRunId(runId));
        return steps.save(new KnowledgeRunStep(runId, index, type, callId, toolName, input)).getId();
    }
    @Transactional public void succeedStep(String stepId, String output, String summary, long durationMs) { KnowledgeRunStep step = steps.findById(stepId).orElseThrow(); step.succeed(output, summary, durationMs); steps.save(step); }
    @Transactional public void failStep(String stepId, String code, String message, long durationMs) { KnowledgeRunStep step = steps.findById(stepId).orElseThrow(); step.fail(code, message, durationMs); steps.save(step); }
    @Transactional public void persistLedger(String runId, AgentEvidenceLedger ledger) {
        for (AgentEvidenceLedger.EvidenceSource source : ledger.all()) {
            if (!sources.existsByKnowledgeRunIdAndSourceRef(runId, source.ref())) sources.save(new KnowledgeRunSource(runId, source));
        }
    }
    @Transactional(readOnly = true) public List<AgentEvidenceLedger.EvidenceSource> storedSources(String runId) {
        return sources.findByKnowledgeRunIdOrderByCreatedAtAsc(runId).stream().map(KnowledgeRunSource::toEvidenceSource).toList();
    }
    @Transactional public void fail(String runId, String message) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.fail(shorten(message)); runs.save(run); recordSettled(run); notifySettled(run); }
    @Transactional public void budgetExhausted(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.budgetExhausted("Agent execution budget exhausted before a valid final answer"); runs.save(run); recordSettled(run); notifySettled(run); }
    @Transactional public void timedOut(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.timedOut("Agent execution exceeded the configured time limit"); runs.save(run); recordSettled(run); notifySettled(run); }

    @Transactional
    public void completeAgent(String runId, JsonNode result, AgentEvidenceLedger ledger) {
        KnowledgeRun run = runs.findById(runId).orElseThrow(); Set<String> persisted = new HashSet<>();
        for (int findingIndex = 0; findingIndex < result.path("findings").size(); findingIndex++) {
            JsonNode finding = result.path("findings").get(findingIndex);
            for (JsonNode citation : finding.path("evidence")) {
                String ref = citation.path("sourceRef").asText(null); AgentEvidenceLedger.EvidenceSource source;
                try { source = ledger.require(ref); } catch (IllegalArgumentException exception) { throw evidenceRejected("INVALID_EVIDENCE", exception.getMessage()); }
                validateSource(run, source);
                String path = "/findings/" + findingIndex;
                if (persisted.add(path + ":" + ref)) evidence.save(new KnowledgeRunEvidence(runId, source.kind(), ref, source.documentId(), source.taskId(), source.chunkId(),
                        path, source.segmentId(), source.label(), source.url()));
            }
        }
        try {
            run.succeed(mapper.writeValueAsString(result)); runs.save(run);
            if (metrics != null && result.path("coverage").isObject()) metrics.coverage(mapper.treeToValue(result.path("coverage"), AgentExecutionContext.Coverage.class));
            recordSettled(run); notifySettled(run);
        }
        catch (Exception exception) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_RESULT_INVALID", "Cannot persist the final Agent answer"); }
    }

    @Transactional
    public void completeAgentStep(String stepId, String output, String summary, long durationMs,
                                  String runId, JsonNode result, AgentEvidenceLedger ledger) {
        completeAgent(runId, result, ledger);
        KnowledgeRunStep step = steps.findById(stepId).orElseThrow();
        step.succeed(output, summary, durationMs); steps.save(step);
    }

    private void validateSource(KnowledgeRun run, AgentEvidenceLedger.EvidenceSource source) {
        if (source.kind() == EvidenceSourceKind.EXTERNAL) return;
        KnowledgeRunDocument scoped = runDocuments.findByKnowledgeRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(value -> value.getTranscriptionTaskId().equals(source.taskId())).findFirst()
                .orElseThrow(() -> evidenceRejected("EVIDENCE_OUTSIDE_SCOPE", "Evidence is outside the immutable Agent scope"));
        if (source.documentId() != null && !Objects.equals(source.documentId(), scoped.getKnowledgeDocumentId())) {
            throw evidenceRejected("EVIDENCE_DOCUMENT_MISMATCH", "Evidence document does not match the Run scope snapshot");
        }
        if (source.kind() == EvidenceSourceKind.DOCUMENT_METADATA) return;
        JsonNode metadata;
        try { metadata = mapper.readTree(scoped.getMetadataSnapshot()); }
        catch (Exception exception) { throw evidenceRejected("SCOPE_SNAPSHOT_INVALID", "Run scope snapshot is invalid"); }
        TranscriptSegment segment = segments.findById(source.segmentId())
                .filter(value -> value.getTranscriptionTaskId().equals(scoped.getTranscriptionTaskId())
                        && value.getTranscriptVersion() == metadata.path("transcriptVersion").asInt())
                .orElseThrow(() -> evidenceRejected("EVIDENCE_SEGMENT_INVALID", "Referenced transcript Segment was not read from the scoped transcript version"));
        if (source.chunkId() != null) {
            KnowledgeChunk chunk = chunks.findById(source.chunkId())
                    .filter(value -> Objects.equals(value.getKnowledgeDocumentId(), scoped.getKnowledgeDocumentId())
                            && Objects.equals(value.getKnowledgeIndexVersionId(), scoped.getKnowledgeIndexVersionId()))
                    .orElseThrow(() -> evidenceRejected("EVIDENCE_CHUNK_INVALID", "Referenced Chunk is outside the scoped index version"));
            try {
                List<String> ids = mapper.readerForListOf(String.class).readValue(chunk.getSegmentIds());
                if (!ids.contains(segment.getId())) throw evidenceRejected("EVIDENCE_PATH_INVALID", "Referenced Segment does not belong to the cited Chunk");
            } catch (ApiException exception) { throw exception; }
            catch (Exception exception) { throw evidenceRejected("EVIDENCE_PATH_INVALID", "Stored Chunk evidence path is invalid"); }
        }
    }

    /** Legacy completion and evidence boundary. */
    @Transactional
    public void complete(String runId, String rawResult, Collection<KnowledgeSearchService.ReadableChunk> readableChunks) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        try {
            JsonNode parsed = mapper.readTree(rawResult);
            if (!parsed.isObject() || !parsed.path("answer").isTextual() || !parsed.path("findings").isArray()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_RESULT_INVALID", "Knowledge agent must return answer and findings JSON");
            Map<String, KnowledgeSearchService.ReadableChunk> allowed = new HashMap<>(); readableChunks.forEach(chunk -> allowed.put(chunk.chunkId(), chunk));
            int citations = 0; Set<String> persistedEvidence = new HashSet<>();
            for (int findingIndex = 0; findingIndex < parsed.path("findings").size(); findingIndex++) for (JsonNode citation : parsed.path("findings").get(findingIndex).path("evidence")) {
                String chunkId = citation.path("chunkId").asText(null); String segmentId = citation.path("segmentId").asText(null); KnowledgeSearchService.ReadableChunk chunk = allowed.get(chunkId);
                if (chunk == null || segmentId == null || !chunk.segmentIds().contains(segmentId)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EVIDENCE", "Knowledge agent cited a segment it did not read");
                String path = "/findings/" + findingIndex;
                if (persistedEvidence.add(path + ":" + segmentId)) { evidence.save(new KnowledgeRunEvidence(run.getId(), chunk.documentId(), chunk.chunkId(), path, segmentId)); citations++; }
            }
            if (!allowed.isEmpty() && citations == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_EVIDENCE", "Knowledge answer must cite source transcript segments");
            run.succeed(mapper.writeValueAsString(parsed)); runs.save(run); notifySettled(run);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_RESULT_INVALID", "Knowledge agent did not return valid JSON"); }
    }

    private AgentSkill requireSkill(String id) {
        try { return skills.require(id); } catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SKILL_NOT_FOUND", exception.getMessage()); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize Agent state", exception); } }
    private void completeIdempotency(IdempotencyRecord record, KnowledgeRun run, Object view) {
        try { idempotency.complete(record, run.getId(), mapper.writeValueAsString(view)); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent Agent response", exception); }
    }
    private static String shorten(String message) { return message == null ? "AGENT_FAILED" : message.substring(0, Math.min(1000, message.length())); }
    private ApiException evidenceRejected(String code, String message) { if (metrics != null) metrics.evidenceRejected(code); return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
    private void recordSettled(KnowledgeRun run) { if (metrics != null && !run.isLegacy()) metrics.settled(run.getStatus()); }
    private void notifySettled(KnowledgeRun run) {
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("knowledge_run", run.getId(), EventType.PROGRESS_CHANGED);
        else progressEvents.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "knowledge-run-settled", run.getId()));
    }

    private record ResolvedDocument(TranscriptionTask task, KnowledgeDocument document) { }
    public record AgentScopeCommand(AgentScopeType type, List<String> transcriptionTaskIds) { }
    public record CreateAgentCommand(String question, AgentScopeCommand scope, String skillId, String timeZone) { }
    public record RunWork(String runId, String ownerId, String question, boolean legacy) { }
    public record KnowledgeRunView(String id, KnowledgeRunStatus status, int toolCallsUsed, int maxToolCalls, String resultDocument, String failureMessage) {
        public static KnowledgeRunView from(KnowledgeRun run) { return new KnowledgeRunView(run.getId(), run.getStatus(), run.getToolCallsUsed(), run.getMaxToolCalls(), run.getResultDocument(), run.getFailureMessage()); }
    }
    public record AgentRunView(String id, String question, KnowledgeRunStatus status, AgentScopeType scopeType, String skillId, String skillVersion,
                               int scopeDocumentCount, int modelCallsUsed, int maxModelCalls, int agentTurnsUsed, int maxAgentTurns,
                               int toolCallsUsed, int maxToolCalls, String resultDocument, String failureMessage, Instant createdAt) {
        public static AgentRunView from(KnowledgeRun run, int scopeCount) { return new AgentRunView(run.getId(), run.getQuestion(), run.getStatus(), run.getScopeType(), run.getSkillId(), run.getSkillVersion(),
                scopeCount, run.getModelCallsUsed(), run.getMaxModelCalls(), run.getAgentTurnsUsed(), run.getMaxAgentTurns(), run.getToolCallsUsed(), run.getMaxToolCalls(), run.getResultDocument(), run.getFailureMessage(), run.getCreatedAt()); }
    }
    public record AgentStepView(int index, AgentStepType type, AgentStepStatus status, String toolName, String summary, String errorCode, String errorMessage, Long durationMs, Instant createdAt) {
        public static AgentStepView from(KnowledgeRunStep step) { return new AgentStepView(step.getStepIndex(), step.getStepType(), step.getStatus(), step.getToolName(), step.getSummaryText(), step.getErrorCode(), step.getErrorMessage(), step.getDurationMs(), step.getCreatedAt()); }
    }
}
