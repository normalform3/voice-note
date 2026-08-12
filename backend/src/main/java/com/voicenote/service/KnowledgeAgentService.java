package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.*;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class KnowledgeAgentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAgentService.class);
    private static final String CREATE_OPERATION = "CREATE_KNOWLEDGE_RUN";
    private static final String CREATE_AGENT_OPERATION = "CREATE_AGENT_RUN";
    private static final String REPLAY_AGENT_OPERATION = "REPLAY_AGENT_RUN";
    private static final int LEGACY_MAX_TOOL_CALLS = 4;
    private final KnowledgeRunRepository runs;
    private final KnowledgeRunEvidenceRepository evidence;
    private final KnowledgeRunDocumentRepository runDocuments;
    private final KnowledgeRunStepRepository steps;
    private final KnowledgeRunSourceRepository sources;
    private final TranscriptionTaskRepository tasks;
    private final KnowledgeDocumentRepository documents;
    private final OrganizedDocumentRepository organizedDocuments;
    private final OrganizedDocumentBlockRepository organizedBlocks;
    private final KnowledgeIndexVersionRepository indexVersions;
    private final KnowledgeChunkRepository chunks;
    private final TranscriptSegmentRepository segments;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final AppProperties properties;
    private final ProgressEventPublisher progressEvents;
    private final AgentSkillRegistry skills;
    private final AgentMetrics metrics;
    private final AgentCheckpointStore checkpoints;
    private final DocumentQaPolicy qaPolicy;
    private final UserMemoryRepository userMemories;
    private final UserMemoryVersionRepository userMemoryVersions;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence,
                                 KnowledgeRunDocumentRepository runDocuments, KnowledgeRunStepRepository steps,
                                 KnowledgeRunSourceRepository sources, TranscriptionTaskRepository tasks, KnowledgeDocumentRepository documents,
                                 OrganizedDocumentRepository organizedDocuments, OrganizedDocumentBlockRepository organizedBlocks,
                                 KnowledgeIndexVersionRepository indexVersions,
                                 KnowledgeChunkRepository chunks, TranscriptSegmentRepository segments,
                                 IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties,
                                 ProgressEventPublisher progressEvents, AgentSkillRegistry skills, AgentMetrics metrics,
                                 AgentCheckpointStore checkpoints, DocumentQaPolicy qaPolicy,
                                 UserMemoryRepository userMemories, UserMemoryVersionRepository userMemoryVersions) {
        this.runs = runs; this.evidence = evidence; this.runDocuments = runDocuments; this.steps = steps; this.sources = sources;
        this.tasks = tasks; this.documents = documents; this.organizedDocuments = organizedDocuments; this.organizedBlocks = organizedBlocks;
        this.indexVersions = indexVersions; this.chunks = chunks; this.segments = segments;
        this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
        this.progressEvents = progressEvents; this.skills = skills; this.metrics = metrics;
        this.checkpoints = checkpoints; this.qaPolicy = qaPolicy; this.userMemories = userMemories; this.userMemoryVersions = userMemoryVersions;
    }

    KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence,
                          KnowledgeRunDocumentRepository runDocuments, KnowledgeRunStepRepository steps,
                          KnowledgeRunSourceRepository sources, TranscriptionTaskRepository tasks, KnowledgeDocumentRepository documents,
                          OrganizedDocumentRepository organizedDocuments, OrganizedDocumentBlockRepository organizedBlocks,
                          KnowledgeIndexVersionRepository indexVersions, KnowledgeChunkRepository chunks, TranscriptSegmentRepository segments,
                          IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties,
                          ProgressEventPublisher progressEvents, AgentSkillRegistry skills, AgentMetrics metrics,
                          AgentCheckpointStore checkpoints, DocumentQaPolicy qaPolicy) {
        this(runs, evidence, runDocuments, steps, sources, tasks, documents, organizedDocuments, organizedBlocks,
                indexVersions, chunks, segments, idempotency, outbox, mapper, properties, progressEvents, skills, metrics,
                checkpoints, qaPolicy, null, null);
    }

    /** Compatibility constructor used by focused legacy evidence tests. */
    KnowledgeAgentService(KnowledgeRunRepository runs, KnowledgeRunEvidenceRepository evidence, IdempotencyService idempotency,
                          OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this(runs, evidence, null, null, null, null, null, null, null, null, null, null,
                idempotency, outbox, mapper, properties, new ProgressEventPublisher(event -> { }), null, null, null, new DocumentQaPolicy(), null, null);
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
        AgentSkill selected;
        boolean frozenSkill = command.frozenSkillSnapshot() != null && !command.frozenSkillSnapshot().isBlank();
        if (frozenSkill) {
            try { selected = mapper.readValue(command.frozenSkillSnapshot(), AgentSkill.class); }
            catch (Exception exception) { throw new ApiException(HttpStatus.CONFLICT, "CONVERSATION_SKILL_INVALID", "The conversation Skill snapshot is invalid"); }
        } else selected = command.skillId() == null || command.skillId().isBlank() ? skills.fallback() : requireSkill(ownerId, command.skillId());
        boolean auto = !frozenSkill && (command.skillId() == null || command.skillId().isBlank());
        if (!auto && !skills.compatible(selected, scope.type(), resolved.stream().map(value -> value.task().getSceneType()).toList())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SKILL_INCOMPATIBLE", "The selected Skill is not compatible with this document scene or scope");
        }
        String snapshot = frozenSkill ? command.frozenSkillSnapshot() : json(selected); String hash = Hashing.sha256(snapshot);
        KnowledgeRun run = runs.save(new KnowledgeRun(ownerId, question, properties.getDashscope().getChatModel(), scope.type(), zone.getId(),
                auto ? "auto" : selected.id(), auto ? "pending" : selected.version(), auto ? null : selected.versionId(), snapshot, hash,
                properties.getAgent().getMaxModelCalls(), properties.getAgent().getMaxTurns(), properties.getAgent().getMaxToolCalls(),
                properties.getAgent().getTimeoutSeconds() * 1000L));
        if (command.conversationId() != null && command.conversationTurnIndex() != null) {
            run.useConversation(command.conversationId(), command.conversationTurnIndex(), Boolean.TRUE.equals(command.memoryEnabled()));
            run = runs.save(run);
        }
        for (ResolvedDocument document : resolved) runDocuments.save(new KnowledgeRunDocument(run.getId(), document.task().getId(),
                document.document() == null ? null : document.document().getId(), document.indexVersion() == null ? null : document.indexVersion().getId(), metadata(document)));
        outbox.enqueue("knowledge_run", run.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);
        completeIdempotency(record, run, AgentRunView.from(run, resolved.size(), skillDisplayName(run))); return run;
    }

    private List<ResolvedDocument> resolveScope(String ownerId, AgentScopeCommand scope) {
        LinkedHashSet<String> requested = new LinkedHashSet<>(scope.transcriptionTaskIds() == null ? List.of() : scope.transcriptionTaskIds());
        List<ResolvedDocument> resolved = new ArrayList<>();
        if (scope.type() == AgentScopeType.CURRENT_DOCUMENT) {
            if (requested.size() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_DOCUMENT_REQUIRED", "CURRENT_DOCUMENT requires exactly one transcriptionTaskId");
            TranscriptionTask task = ownedTask(ownerId, requested.iterator().next());
            if (!task.isTranscriptReady()) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Current document questions require a persisted transcript");
            resolved.add(currentDocument(task));
        } else if (scope.type() == AgentScopeType.SELECTED_DOCUMENTS || scope.frozen()) {
            if (requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "SELECTED_DOCUMENTS_REQUIRED", "SELECTED_DOCUMENTS requires at least one transcriptionTaskId");
            for (String taskId : requested) resolved.add(indexed(ownerId, taskId));
        } else {
            if (!requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "ALL_DOCUMENTS_HAS_IDS", "ALL_DOCUMENTS must not include transcriptionTaskIds");
            LinkedHashSet<String> seenTasks = new LinkedHashSet<>();
            for (KnowledgeDocument document : documents.findByOwnerIdOrderByUpdatedAtDesc(ownerId)) {
                if (!seenTasks.add(document.getTranscriptionTaskId())) continue;
                TranscriptionTask task = ownedTask(ownerId, document.getTranscriptionTaskId());
                KnowledgeIndexVersion index = activeIndex(task, document);
                if (index == null) continue;
                resolved.add(new ResolvedDocument(task, document, organizedForIndex(index), index, QaRetrievalMode.HYBRID_INDEX));
            }
        }
        if (resolved.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "AGENT_SCOPE_EMPTY", "No ready documents are available in this scope");
        if (resolved.size() > properties.getAgent().getMaxScopeDocuments()) throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SCOPE_TOO_LARGE", "Agent scope exceeds the configured document limit");
        return List.copyOf(resolved);
    }

    @Transactional(readOnly = true)
    public List<String> resolveScopeTaskIds(String ownerId, AgentScopeCommand scope) {
        return resolveScope(ownerId, scope).stream().map(value -> value.task().getId()).toList();
    }

    private ResolvedDocument indexed(String ownerId, String taskId) {
        TranscriptionTask task = ownedTask(ownerId, taskId);
        KnowledgeDocument document = documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(ownerId, taskId, task.getTranscriptVersion()).orElse(null);
        KnowledgeIndexVersion index = activeIndex(task, document);
        if (index == null) throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_SEARCHABLE", "Selected documents must have an active knowledge index");
        return new ResolvedDocument(task, document, organizedForIndex(index), index, QaRetrievalMode.HYBRID_INDEX);
    }

    private ResolvedDocument currentDocument(TranscriptionTask task) {
        OrganizedDocument organized = organizedDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(
                task.getOwnerId(), task.getId(), task.getTranscriptVersion()).filter(value -> qaPolicy.hasReadyFormalDocument(task, value)).orElse(null);
        KnowledgeDocument document = documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(
                task.getOwnerId(), task.getId(), task.getTranscriptVersion()).orElse(null);
        KnowledgeIndexVersion index = activeIndex(task, document);
        DocumentQaPolicy.Capabilities capabilities = qaPolicy.evaluate(task, organized, document, index);
        if (capabilities.currentMode() == QaRetrievalMode.HYBRID_INDEX) organized = organizedForIndex(index);
        return new ResolvedDocument(task, index == null ? null : document, organized, index, capabilities.currentMode());
    }

    private KnowledgeIndexVersion activeIndex(TranscriptionTask task, KnowledgeDocument document) {
        if (document == null || document.getActiveIndexVersionId() == null) return null;
        KnowledgeIndexVersion index = indexVersions.findById(document.getActiveIndexVersionId()).orElse(null);
        return qaPolicy.hasActiveIndex(task, document, index) ? index : null;
    }

    private OrganizedDocument organizedForIndex(KnowledgeIndexVersion index) {
        return index == null ? null : organizedDocuments.findById(index.getOrganizedDocumentId()).orElse(null);
    }

    private TranscriptionTask ownedTask(String ownerId, String taskId) {
        return tasks.findById(taskId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
    }

    private String metadata(ResolvedDocument value) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            String title = value.document() != null ? value.document().getTitle()
                    : value.organized() != null ? value.organized().getTitle()
                    : Objects.toString(value.task().getSubject(), "录音 " + value.task().getId().substring(0, 8));
            snapshot.put("title", title);
            snapshot.put("occurredAt", value.task().getOccurredAt()); snapshot.put("sceneType", value.task().getSceneType().name());
            snapshot.put("subject", value.task().getSubject()); snapshot.put("tags", mapper.readTree(value.task().getTags() == null ? "[]" : value.task().getTags()));
            snapshot.put("transcriptVersion", value.task().getTranscriptVersion()); snapshot.put("speakerCorrectionRevision", value.task().getSpeakerCorrectionRevision());
            snapshot.put("retrievalMode", value.mode().name());
            if (value.organized() != null) {
                snapshot.put("organizedDocumentId", value.organized().getId()); snapshot.put("organizedDocumentVersion", value.organized().getVersion());
                if (value.mode() == QaRetrievalMode.FORMAL_OVERVIEW) snapshot.put("formalOverview", formalOverview(value.organized()));
            }
            return mapper.writeValueAsString(snapshot);
        } catch (Exception exception) { throw new IllegalStateException("Cannot snapshot Agent document metadata", exception); }
    }

    private JsonNode formalOverview(OrganizedDocument document) {
        var output = mapper.createObjectNode(); output.put("title", document.getTitle());
        output.put("summary", shortenText(Objects.toString(document.getSummaryText(), ""), 800));
        var topics = output.putArray("topics"); int total = 0;
        for (OrganizedDocumentBlock block : organizedBlocks.findByOrganizedDocumentIdOrderByBlockIndex(document.getId())) {
            if (block.getBlockType() != OrganizedBlockType.TOPIC) continue;
            total++; if (topics.size() >= 20) continue;
            var topic = topics.addObject(); topic.put("title", Objects.toString(block.getTopicTitle(), "整理片段"));
            topic.put("content", shortenText(Objects.toString(block.getSummaryText(), block.getTextContent()), 500));
            topic.put("startMs", block.getStartMs()); topic.put("endMs", block.getEndMs());
            var fragments = topic.putArray("sourceFragments"); JsonNode raw = parseArray(block.getSourceFragments()); int count = 0;
            for (JsonNode fragment : raw) {
                String segmentId = fragment.path("segmentId").asText(null); if (segmentId == null || segmentId.isBlank()) continue;
                if (count++ >= 3) break;
                var stored = fragments.addObject(); stored.put("segmentId", segmentId);
                stored.put("speakerId", fragment.path("speakerId").asText(null)); stored.put("startMs", fragment.path("startMs").asLong());
                stored.put("endMs", fragment.path("endMs").asLong()); stored.put("text", shortenText(fragment.path("text").asText(""), 800));
            }
        }
        output.put("topicCount", total); return output;
    }

    private JsonNode parseArray(String value) {
        if (value == null || value.isBlank()) return mapper.createArrayNode();
        try { JsonNode parsed = mapper.readTree(value); return parsed.isArray() ? parsed : mapper.createArrayNode(); }
        catch (Exception exception) { return mapper.createArrayNode(); }
    }

    private static String shortenText(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "…"; }

    @Transactional public void markQueued(String runId) { runs.findById(runId).orElseThrow().queue(); }
    @Transactional(readOnly = true) public List<KnowledgeRun> ownedRuns(String ownerId) { return runs.findByOwnerIdOrderByCreatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public KnowledgeRun ownedRun(String ownerId, String runId) {
        return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "KNOWLEDGE_RUN_NOT_FOUND", "Knowledge task was not found"));
    }

    @Transactional
    public void deleteRunGraph(String ownerId, String runId) {
        ownedRun(ownerId, runId);
        for (KnowledgeRun child : runs.findByParentRunIdOrderByCreatedAtAsc(runId)) deleteRunGraph(ownerId, child.getId());
        evidence.deleteByKnowledgeRunId(runId);
        sources.deleteByKnowledgeRunId(runId);
        if (checkpoints != null) checkpoints.delete(runId);
        steps.deleteByKnowledgeRunId(runId);
        runDocuments.deleteByKnowledgeRunId(runId);
        idempotency.deleteResource(ownerId, runId);
        outboxEventsForRun(runId);
        runs.deleteById(runId);
    }

    private void outboxEventsForRun(String runId) {
        outbox.deleteAggregate("knowledge_run", runId);
    }
    public String skillDisplayName(KnowledgeRun run) {
        if (run == null || "pending".equals(run.getSkillVersion()) || run.getSkillSnapshot() == null) return null;
        try { return mapper.readTree(run.getSkillSnapshot()).path("displayName").asText(null); }
        catch (Exception ignored) { return null; }
    }
    @Transactional(readOnly = true) public List<KnowledgeRunDocument> runDocuments(String runId) { return runDocuments.findByKnowledgeRunIdOrderByCreatedAtAsc(runId); }
    @Transactional(readOnly = true) public List<KnowledgeRunStep> runSteps(String runId) { return steps.findByKnowledgeRunIdOrderByStepIndexAsc(runId); }
    @Transactional(readOnly = true) public List<AgentCheckpoint> runCheckpoints(String runId) { return checkpoints == null ? List.of() : checkpoints.list(runId); }
    @Transactional(readOnly = true) public List<String> childRunIds(String ownerId, String runId) {
        return runs.findByParentRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(run -> run.getOwnerId().equals(ownerId)).map(KnowledgeRun::getId).toList();
    }
    @Transactional(readOnly = true) public List<String> queuedRunIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(runs.findTop10ByStatusOrderByCreatedAtAsc(KnowledgeRunStatus.QUEUED).stream().map(KnowledgeRun::getId).toList());
        ids.addAll(runs.findTop10ByStatusAndLeaseUntilBeforeOrderByCreatedAtAsc(KnowledgeRunStatus.RUNNING, Instant.now()).stream().map(KnowledgeRun::getId).toList());
        return List.copyOf(ids);
    }
    @Transactional public RunWork claim(String runId) {
        KnowledgeRun run = runs.findById(runId).orElse(null);
        if (run == null) return null;
        boolean recovered = run.getStatus() == KnowledgeRunStatus.RUNNING;
        if (!run.start()) return null;
        if (recovered && !run.isLegacy()) {
            steps.findByKnowledgeRunIdAndStatus(runId, AgentStepStatus.RUNNING).forEach(step -> {
                step.interrupt("Worker lease expired before the step committed"); steps.save(step);
            });
            KnowledgeRunStep recovery = new KnowledgeRunStep(runId, run.allocateStepIndex(), AgentStepType.RECOVERY,
                    null, null, json(Map.of("checkpointId", Objects.toString(run.getCurrentCheckpointId(), ""),
                    "recoveryCount", run.getRecoveryCount())), run.getExecutionEpoch(), run.getCurrentCheckpointId());
            recovery.succeed(json(Map.of("executionEpoch", run.getExecutionEpoch())), "已从最近的 Checkpoint 恢复", 0);
            steps.save(recovery);
        }
        runs.save(run);
        return new RunWork(run.getId(), run.getOwnerId(), run.getQuestion(), run.isLegacy(), run.getExecutionEpoch(), recovered);
    }
    @Transactional public void selectSkill(String runId, AgentSkill skill) {
        KnowledgeRun run = runs.findById(runId).orElseThrow(); String snapshot = json(skill);
        run.selectSkill(skill.id(), skill.version(), skill.versionId(), snapshot, Hashing.sha256(snapshot)); runs.save(run);
    }
    @Transactional public void selectSkill(String runId, long epoch, AgentSkill skill) {
        KnowledgeRun run = requireExecution(runId, epoch); String snapshot = json(skill);
        run.selectSkill(skill.id(), skill.version(), skill.versionId(), snapshot, Hashing.sha256(snapshot)); runs.save(run);
    }
    @Transactional public boolean consumeModel(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); boolean value = run.consumeModelCall(); runs.save(run); return value; }
    @Transactional public boolean consumeTurn(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); boolean value = run.consumeTurn(); runs.save(run); return value; }
    @Transactional public void consumeTool(String runId) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        if (!run.consumeTool()) { runs.save(run); throw new ApiException(HttpStatus.CONFLICT, "AGENT_TOOL_BUDGET_EXHAUSTED", "Knowledge agent tool budget exhausted"); }
        run.renewLease(); runs.save(run);
    }
    @Transactional public String beginStep(String runId, AgentStepType type, String callId, String toolName, String input) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        KnowledgeRunStep step = steps.save(new KnowledgeRunStep(runId, run.allocateStepIndex(), type, callId, toolName,
                traceDocument(input), run.getExecutionEpoch(), run.getCurrentCheckpointId()));
        runs.save(run); return step.getId();
    }
    @Transactional public void succeedStep(String stepId, String output, String summary, long durationMs) { KnowledgeRunStep step = steps.findById(stepId).orElseThrow(); step.succeed(traceDocument(output), summary, durationMs); steps.save(step); }
    @Transactional public void failStep(String stepId, String code, String message, long durationMs) { KnowledgeRunStep step = steps.findById(stepId).orElseThrow(); step.fail(code, traceMessage(message), durationMs); steps.save(step); }
    @Transactional public void persistLedger(String runId, AgentEvidenceLedger ledger) {
        for (AgentEvidenceLedger.EvidenceSource source : ledger.all()) {
            if (!sources.existsByKnowledgeRunIdAndSourceRef(runId, source.ref())) sources.save(new KnowledgeRunSource(runId, source));
        }
    }
    @Transactional(readOnly = true) public List<AgentEvidenceLedger.EvidenceSource> storedSources(String runId) {
        return sources.findByKnowledgeRunIdOrderByCreatedAtAsc(runId).stream().map(KnowledgeRunSource::toEvidenceSource).toList();
    }
    @Transactional public void fail(String runId, String message) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.fail(shorten(traceMessage(message))); runs.save(run); recordSettled(run); notifySettled(run); }
    @Transactional public void budgetExhausted(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.budgetExhausted("Agent execution budget exhausted before a valid final answer"); runs.save(run); recordSettled(run); notifySettled(run); }
    @Transactional public void timedOut(String runId) { KnowledgeRun run = runs.findById(runId).orElseThrow(); run.timedOut("Agent execution exceeded the configured time limit"); runs.save(run); recordSettled(run); notifySettled(run); }

    @Transactional(readOnly = true)
    public AgentState loadCurrentState(String runId, long epoch) {
        KnowledgeRun run = requireExecution(runId, epoch);
        if (run.getCurrentCheckpointId() == null) return null;
        AgentCheckpoint checkpoint = checkpoints.require(run.getCurrentCheckpointId());
        if (!checkpoint.getKnowledgeRunId().equals(runId)) throw new AgentCheckpointStore.CheckpointException("CHECKPOINT_RUN_MISMATCH", "Checkpoint does not belong to the Agent Run");
        return checkpoints.read(checkpoint);
    }

    @Transactional
    public AgentCheckpoint saveInitialCheckpoint(String runId, long epoch, AgentState state, boolean replayable) {
        KnowledgeRun run = requireExecution(runId, epoch);
        if (run.getCurrentCheckpointId() != null) return checkpoints.require(run.getCurrentCheckpointId());
        AgentCheckpoint checkpoint = checkpoints.save(run, state, null, replayable);
        runs.save(run); return checkpoint;
    }

    @Transactional
    public StepWork beginRoutingStep(String runId, long epoch, String input) {
        KnowledgeRun run = requireExecution(runId, epoch);
        if (!run.consumeModelCall()) return null;
        return beginAgentStep(run, AgentStepType.ROUTE, null, null, input);
    }

    @Transactional
    public StepWork beginModelStep(String runId, long epoch, String input) {
        KnowledgeRun run = requireExecution(runId, epoch);
        if (!run.consumeModelTurn()) return null;
        return beginAgentStep(run, AgentStepType.MODEL, null, null, input);
    }

    @Transactional
    public StepWork beginToolStep(String runId, long epoch, AgentStepType type, String callId, String toolName,
                                  String input, boolean chargeBudget) {
        KnowledgeRun run = requireExecution(runId, epoch);
        if (chargeBudget && !run.consumeAgentTool()) return null;
        return beginAgentStep(run, type, callId, toolName, input);
    }

    private StepWork beginAgentStep(KnowledgeRun run, AgentStepType type, String callId, String toolName, String input) {
        KnowledgeRunStep step = steps.save(new KnowledgeRunStep(run.getId(), run.allocateStepIndex(), type, callId,
                toolName, traceDocument(input), run.getExecutionEpoch(), run.getCurrentCheckpointId()));
        runs.save(run);
        return new StepWork(step.getId(), step.getStepIndex(), run.getExecutionEpoch(), run.getCurrentCheckpointId());
    }

    @Transactional
    public AgentCheckpoint succeedAgentStep(String runId, long epoch, String stepId, String output, String summary,
                                             long durationMs, AgentModelClient.AgentUsage usage, String finishReason,
                                             AgentState state, AgentEvidenceLedger ledger, boolean replayable) {
        KnowledgeRun run = requireExecution(runId, epoch);
        KnowledgeRunStep step = requireStep(runId, epoch, stepId);
        step.succeed(traceDocument(output), summary, durationMs);
        if (usage != null) step.modelUsage(finishReason, usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
        else if (finishReason != null) step.modelUsage(finishReason, null, null, null);
        run.addActiveDuration(durationMs);
        if (ledger != null) persistLedgerInternal(runId, ledger);
        AgentCheckpoint checkpoint = checkpoints.save(run, state, stepId, replayable);
        step.useOutputCheckpoint(checkpoint.getId());
        steps.save(step); runs.save(run);
        return checkpoint;
    }

    @Transactional
    public AgentCheckpoint completeRouteStep(String runId, long epoch, String stepId, AgentSkill skill,
                                             String output, String summary, long durationMs,
                                             AgentModelClient.AgentUsage usage, String finishReason,
                                             String handledErrorCode, String handledErrorMessage, AgentState state) {
        KnowledgeRun run = requireExecution(runId, epoch);
        KnowledgeRunStep step = requireStep(runId, epoch, stepId);
        String snapshot = json(skill);
        run.selectSkill(skill.id(), skill.version(), skill.versionId(), snapshot, Hashing.sha256(snapshot));
        if (handledErrorCode == null) step.succeed(traceDocument(output), summary, durationMs);
        else step.fail(handledErrorCode, shorten(traceMessage(handledErrorMessage)), durationMs);
        if (usage != null) step.modelUsage(finishReason, usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
        run.addActiveDuration(durationMs);
        AgentCheckpoint checkpoint = checkpoints.save(run, state, stepId, true);
        step.useOutputCheckpoint(checkpoint.getId());
        steps.save(step); runs.save(run);
        return checkpoint;
    }

    @Transactional
    public AgentCheckpoint failObservedStep(String runId, long epoch, String stepId, String code, String message,
                                            long durationMs, AgentState state, AgentEvidenceLedger ledger, boolean replayable) {
        KnowledgeRun run = requireExecution(runId, epoch);
        KnowledgeRunStep step = requireStep(runId, epoch, stepId);
        step.fail(code, shorten(traceMessage(message)), durationMs); run.addActiveDuration(durationMs);
        if (ledger != null) persistLedgerInternal(runId, ledger);
        AgentCheckpoint checkpoint = checkpoints.save(run, state, stepId, replayable);
        step.useOutputCheckpoint(checkpoint.getId());
        steps.save(step); runs.save(run);
        return checkpoint;
    }

    @Transactional
    public void failTerminalStep(String runId, long epoch, String stepId, String code, String stage, String message,
                                 long durationMs, AgentState terminalState) {
        KnowledgeRun run = requireExecution(runId, epoch);
        KnowledgeRunStep step = requireStep(runId, epoch, stepId);
        step.fail(code, shorten(traceMessage(message)), durationMs); run.addActiveDuration(durationMs);
        run.fail(code, stage, shorten(traceMessage(message)));
        AgentCheckpoint checkpoint = checkpoints.save(run, terminalState, stepId, false);
        step.useOutputCheckpoint(checkpoint.getId());
        steps.save(step); runs.save(run); recordSettled(run); notifySettled(run);
    }

    @Transactional
    public void budgetExhausted(String runId, long epoch, AgentState terminalState) {
        budgetExhausted(runId, epoch, "AGENT_BUDGET_EXHAUSTED", "BUDGET",
                "Agent execution budget exhausted before a valid final answer", terminalState);
    }

    @Transactional
    public void budgetExhausted(String runId, long epoch, String code, String stage, String message,
                                AgentState terminalState) {
        KnowledgeRun run = requireExecution(runId, epoch);
        run.budgetExhausted(code, stage, message);
        checkpoints.save(run, terminalState, null, false); runs.save(run); recordSettled(run); notifySettled(run);
        log.warn("Agent Run {} exhausted budget: code={}, stage={}, modelCalls={}/{}, turns={}/{}, tools={}/{}",
                runId, code, stage, run.getModelCallsUsed(), run.getMaxModelCalls(), run.getAgentTurnsUsed(),
                run.getMaxAgentTurns(), run.getToolCallsUsed(), run.getMaxToolCalls());
    }

    @Transactional
    public void timedOut(String runId, long epoch, AgentState terminalState) {
        KnowledgeRun run = requireExecution(runId, epoch);
        run.timedOut("Agent execution exceeded the configured active time limit");
        checkpoints.save(run, terminalState, null, false); runs.save(run); recordSettled(run); notifySettled(run);
    }

    @Transactional
    public void failExecution(String runId, long epoch, String code, String stage, String message, AgentState terminalState) {
        KnowledgeRun run = requireExecution(runId, epoch);
        run.fail(code, stage, shorten(traceMessage(message)));
        checkpoints.save(run, terminalState, null, false); runs.save(run); recordSettled(run); notifySettled(run);
    }

    @Transactional
    public void completeAgentStep(String runId, long epoch, String stepId, String output, String summary,
                                  long durationMs, JsonNode result, AgentState terminalState, AgentEvidenceLedger ledger) {
        KnowledgeRun run = requireExecution(runId, epoch);
        KnowledgeRunStep step = requireStep(runId, epoch, stepId);
        Set<String> persisted = new HashSet<>();
        persistLedgerInternal(runId, ledger);
        persistResultEvidence(run, result, "", ledger, persisted);
        try {
            step.succeed(traceDocument(output), summary, durationMs); run.addActiveDuration(durationMs);
            run.succeed(mapper.writeValueAsString(result));
            AgentCheckpoint checkpoint = checkpoints.save(run, terminalState, stepId, false);
            step.useOutputCheckpoint(checkpoint.getId());
            steps.save(step); runs.save(run);
            if (metrics != null && result.path("coverage").isObject()) metrics.coverage(mapper.treeToValue(result.path("coverage"), AgentExecutionContext.Coverage.class));
            recordSettled(run); notifySettled(run);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_RESULT_INVALID", "Cannot persist the final Agent answer"); }
    }

    @Transactional
    public KnowledgeRun replayAgent(String ownerId, String key, String parentRunId, String checkpointId) {
        KnowledgeRun parent = ownedRun(ownerId, parentRunId);
        if (parent.isLegacy()) throw new ApiException(HttpStatus.CONFLICT, "AGENT_REPLAY_UNSUPPORTED", "Legacy Agent Runs cannot be replayed");
        if (!parent.isTerminal()) throw new ApiException(HttpStatus.CONFLICT, "AGENT_RUN_NOT_TERMINAL", "Only settled Agent Runs can be replayed");
        AgentCheckpoint source;
        try { source = checkpoints.require(checkpointId); }
        catch (AgentCheckpointStore.CheckpointException exception) { throw new ApiException(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage()); }
        if (!source.getKnowledgeRunId().equals(parentRunId) || !source.isReplayable()) {
            throw new ApiException(HttpStatus.CONFLICT, "CHECKPOINT_NOT_REPLAYABLE", "Checkpoint is not replayable for this Agent Run");
        }
        AgentState state;
        try { state = checkpoints.read(source); }
        catch (AgentCheckpointStore.CheckpointException exception) { throw new ApiException(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage()); }
        validateReplayState(state);
        IdempotencyRecord record = idempotency.reserve(ownerId, REPLAY_AGENT_OPERATION, key,
                Hashing.canonicalJsonHash(Map.of("parentRunId", parentRunId, "checkpointId", checkpointId)));
        if (record.getResourceId() != null) return ownedRun(ownerId, record.getResourceId());

        KnowledgeRun replay = runs.save(KnowledgeRun.replayOf(parent, checkpointId, state));
        for (AgentState.DocumentSnapshot document : state.documentSnapshots()) {
            runDocuments.save(new KnowledgeRunDocument(replay.getId(), document.transcriptionTaskId(),
                    document.knowledgeDocumentId(), document.knowledgeIndexVersionId(), document.metadataSnapshot()));
        }
        Set<String> allowedSources = new HashSet<>(state.evidenceSourceRefs());
        sources.findByKnowledgeRunIdOrderByCreatedAtAsc(parentRunId).stream()
                .filter(value -> allowedSources.contains(value.getSourceRef()))
                .forEach(value -> sources.save(new KnowledgeRunSource(replay.getId(), value.toEvidenceSource())));
        checkpoints.save(replay, state, null, true); runs.save(replay);
        outbox.enqueue("knowledge_run", replay.getId(), EventType.KNOWLEDGE_RUN_REQUESTED);
        completeIdempotency(record, replay, AgentRunView.from(replay, runDocuments(replay.getId()).size(), skillDisplayName(replay)));
        return replay;
    }

    private void validateReplayState(AgentState state) {
        boolean invalid = state.modelId() == null || state.modelId().isBlank() || state.skillId() == null
                || state.skillVersion() == null || state.skillSnapshot() == null || state.documentSnapshots().isEmpty()
                || state.maxModelCalls() <= 0 || state.modelCallsUsed() < 0 || state.modelCallsUsed() > state.maxModelCalls()
                || state.maxAgentTurns() <= 0 || state.agentTurnsUsed() < 0 || state.agentTurnsUsed() > state.maxAgentTurns()
                || state.maxToolCalls() <= 0 || state.toolCallsUsed() < 0 || state.toolCallsUsed() > state.maxToolCalls()
                || state.maxActiveDurationMs() <= 0 || state.activeDurationMs() < 0 || state.activeDurationMs() > state.maxActiveDurationMs();
        if (!invalid && state.skillHash() != null) invalid = !state.skillHash().equals(Hashing.sha256(state.skillSnapshot()));
        if (invalid) throw new ApiException(HttpStatus.CONFLICT, "CHECKPOINT_INCOMPATIBLE", "Checkpoint does not contain a compatible frozen Agent state");
    }

    @Transactional(readOnly = true)
    public KnowledgeRunStep ownedStep(String ownerId, String runId, String stepId) {
        ownedRun(ownerId, runId);
        return steps.findById(stepId).filter(value -> value.getKnowledgeRunId().equals(runId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AGENT_STEP_NOT_FOUND", "Agent Step was not found"));
    }

    @Transactional(readOnly = true)
    public List<AgentStepView> stepViews(String runId) {
        Map<String, AgentCheckpoint> byStep = new HashMap<>();
        runCheckpoints(runId).stream().filter(value -> value.getStepId() != null).forEach(value -> byStep.put(value.getStepId(), value));
        return runSteps(runId).stream().map(step -> AgentStepView.from(step, byStep.get(step.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentCheckpointView> checkpointViews(String runId) {
        return runCheckpoints(runId).stream().map(AgentCheckpointView::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentRunView agentRunView(KnowledgeRun run) {
        return AgentRunView.from(run, runDocuments(run.getId()).size(), skillDisplayName(run));
    }

    @Transactional(readOnly = true)
    public AgentStepDetailView stepDetail(String ownerId, String runId, String stepId) {
        KnowledgeRunStep step = ownedStep(ownerId, runId, stepId);
        return AgentStepDetailView.from(step, parseDocument(step.getInputDocument()), parseDocument(step.getOutputDocument()));
    }

    private KnowledgeRun requireExecution(String runId, long epoch) {
        KnowledgeRun run = runs.findById(runId).orElseThrow();
        if (run.getStatus() != KnowledgeRunStatus.RUNNING || run.getExecutionEpoch() != epoch) {
            throw new StaleAgentExecutionException();
        }
        return run;
    }

    private KnowledgeRunStep requireStep(String runId, long epoch, String stepId) {
        return steps.findById(stepId).filter(value -> value.getKnowledgeRunId().equals(runId)
                        && value.getExecutionEpoch() == epoch && value.getStatus() == AgentStepStatus.RUNNING)
                .orElseThrow(StaleAgentExecutionException::new);
    }

    private void persistLedgerInternal(String runId, AgentEvidenceLedger ledger) {
        for (AgentEvidenceLedger.EvidenceSource source : ledger.all()) {
            if (!sources.existsByKnowledgeRunIdAndSourceRef(runId, source.ref())) sources.save(new KnowledgeRunSource(runId, source));
        }
    }

    private JsonNode parseDocument(String value) {
        if (value == null || value.isBlank()) return null;
        try { return mapper.readTree(value); }
        catch (Exception exception) { return mapper.createObjectNode().put("unavailable", true); }
    }

    @Transactional
    public void completeAgent(String runId, JsonNode result, AgentEvidenceLedger ledger) {
        KnowledgeRun run = runs.findById(runId).orElseThrow(); Set<String> persisted = new HashSet<>();
        persistResultEvidence(run, result, "", ledger, persisted);
        try {
            run.succeed(mapper.writeValueAsString(result)); runs.save(run);
            if (metrics != null && result.path("coverage").isObject()) metrics.coverage(mapper.treeToValue(result.path("coverage"), AgentExecutionContext.Coverage.class));
            recordSettled(run); notifySettled(run);
        }
        catch (Exception exception) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_RESULT_INVALID", "Cannot persist the final Agent answer"); }
    }

    private void persistResultEvidence(KnowledgeRun run, JsonNode node, String path, AgentEvidenceLedger ledger, Set<String> persisted) {
        if (node.isObject()) {
            JsonNode citations = node.path("evidence");
            if (citations.isArray()) for (JsonNode citation : citations) {
                String ref = citation.path("sourceRef").asText(null); AgentEvidenceLedger.EvidenceSource source;
                try { source = ledger.require(ref); } catch (IllegalArgumentException exception) { throw evidenceRejected("INVALID_EVIDENCE", exception.getMessage()); }
                validateSource(run, source);
                String resultPath = path.isBlank() ? "/" : path;
                if (persisted.add(resultPath + ":" + ref)) evidence.save(new KnowledgeRunEvidence(run.getId(), source.kind(), ref, source.documentId(), source.taskId(), source.chunkId(),
                        resultPath, source.segmentId(), source.memoryId(), source.memoryVersionId(),
                        source.kind() == EvidenceSourceKind.USER_MEMORY ? source.text() : null, source.label(), source.url()));
            }
            node.fields().forEachRemaining(field -> { if (!"evidence".equals(field.getKey())) persistResultEvidence(run, field.getValue(), path + "/" + field.getKey(), ledger, persisted); });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) persistResultEvidence(run, node.get(index), path + "/" + index, ledger, persisted);
        }
    }

    @Transactional
    public void completeAgentStep(String stepId, String output, String summary, long durationMs,
                                  String runId, JsonNode result, AgentEvidenceLedger ledger) {
        completeAgent(runId, result, ledger);
        KnowledgeRunStep step = steps.findById(stepId).orElseThrow();
        step.succeed(traceDocument(output), summary, durationMs); steps.save(step);
    }

    private void validateSource(KnowledgeRun run, AgentEvidenceLedger.EvidenceSource source) {
        if (source.kind() == EvidenceSourceKind.EXTERNAL) return;
        if (source.kind() == EvidenceSourceKind.USER_MEMORY) {
            if (!run.isMemoryEnabled() || userMemories == null || userMemoryVersions == null) throw evidenceRejected("MEMORY_EVIDENCE_DISABLED", "User memory evidence is not enabled for this Run");
            UserMemory memory = userMemories.findById(source.memoryId())
                    .filter(value -> value.getOwnerId().equals(run.getOwnerId()) && value.getStatus() == UserMemoryStatus.ACTIVE
                            && Objects.equals(value.getCurrentVersionId(), source.memoryVersionId()))
                    .orElseThrow(() -> evidenceRejected("MEMORY_EVIDENCE_STALE", "Referenced user memory is missing, deleted, or no longer current"));
            UserMemoryVersion version = userMemoryVersions.findById(source.memoryVersionId())
                    .filter(value -> value.getMemoryId().equals(memory.getId()) && Objects.equals(value.getContent(), source.text()))
                    .orElseThrow(() -> evidenceRejected("MEMORY_EVIDENCE_INVALID", "Referenced user memory version is invalid"));
            return;
        }
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

    private AgentSkill requireSkill(String ownerId, String id) {
        try { return skills.require(ownerId, id); } catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_SKILL_NOT_FOUND", exception.getMessage()); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize Agent state", exception); } }
    private String traceDocument(String value) { return AgentTraceSanitizer.sanitizeJson(mapper, value); }
    private static String traceMessage(String value) { return AgentTraceSanitizer.sanitizeText(value); }
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

    private record ResolvedDocument(TranscriptionTask task, KnowledgeDocument document, OrganizedDocument organized,
                                    KnowledgeIndexVersion indexVersion, QaRetrievalMode mode) { }
    public record AgentScopeCommand(AgentScopeType type, List<String> transcriptionTaskIds, boolean frozen) {
        public AgentScopeCommand(AgentScopeType type, List<String> transcriptionTaskIds) { this(type, transcriptionTaskIds, false); }
    }
    public record CreateAgentCommand(String question, AgentScopeCommand scope, String skillId, String timeZone,
                                     String conversationId, Integer conversationTurnIndex, Boolean memoryEnabled,
                                     String frozenSkillSnapshot) {
        public CreateAgentCommand(String question, AgentScopeCommand scope, String skillId, String timeZone) {
            this(question, scope, skillId, timeZone, null, null, false, null);
        }
    }
    public record RunWork(String runId, String ownerId, String question, boolean legacy, long executionEpoch, boolean recovered) { }
    public record StepWork(String stepId, int stepIndex, long executionEpoch, String inputCheckpointId) { }
    public record KnowledgeRunView(String id, KnowledgeRunStatus status, int toolCallsUsed, int maxToolCalls, String resultDocument, String failureMessage) {
        public static KnowledgeRunView from(KnowledgeRun run) { return new KnowledgeRunView(run.getId(), run.getStatus(), run.getToolCallsUsed(), run.getMaxToolCalls(), run.getResultDocument(), run.getFailureMessage()); }
    }
    public record AgentRunView(String id, String question, KnowledgeRunStatus status, AgentScopeType scopeType, String skillId, String skillVersion, String skillDisplayName,
                               String conversationId, Integer conversationTurnIndex, boolean memoryEnabled,
                               int scopeDocumentCount, int modelCallsUsed, int maxModelCalls, int agentTurnsUsed, int maxAgentTurns,
                               int toolCallsUsed, int maxToolCalls, String resultDocument, String failureMessage,
                               String failureCode, String failureStage, String parentRunId, String rootRunId,
                               String replayFromCheckpointId, int recoveryCount, Instant createdAt, Instant completedAt) {
        public static AgentRunView from(KnowledgeRun run, int scopeCount, String skillDisplayName) { return new AgentRunView(run.getId(), run.getQuestion(), run.getStatus(), run.getScopeType(), run.getSkillId(), run.getSkillVersion(), skillDisplayName,
                run.getConversationId(), run.getConversationTurnIndex(), run.isMemoryEnabled(),
                scopeCount, run.getModelCallsUsed(), run.getMaxModelCalls(), run.getAgentTurnsUsed(), run.getMaxAgentTurns(),
                run.getToolCallsUsed(), run.getMaxToolCalls(), run.getResultDocument(), run.getFailureMessage(), run.getFailureCode(),
                run.getFailureStage(), run.getParentRunId(), run.getRootRunId(), run.getReplayFromCheckpointId(), run.getRecoveryCount(),
                run.getCreatedAt(), run.getCompletedAt()); }
    }
    public record AgentStepView(String id, int index, AgentStepType type, AgentStepStatus status, long executionEpoch,
                                String toolName, String summary, String errorCode, String errorMessage, Long durationMs,
                                String finishReason, Integer inputTokens, Integer outputTokens, Integer totalTokens,
                                String checkpointId, boolean replayable, Instant createdAt, Instant completedAt) {
        public static AgentStepView from(KnowledgeRunStep step) { return from(step, null); }
        public static AgentStepView from(KnowledgeRunStep step, AgentCheckpoint checkpoint) {
            return new AgentStepView(step.getId(), step.getStepIndex(), step.getStepType(), step.getStatus(), step.getExecutionEpoch(),
                    step.getToolName(), step.getSummaryText(), step.getErrorCode(), step.getErrorMessage(), step.getDurationMs(),
                    step.getFinishReason(), step.getInputTokens(), step.getOutputTokens(), step.getTotalTokens(),
                    checkpoint == null ? step.getOutputCheckpointId() : checkpoint.getId(), checkpoint != null && checkpoint.isReplayable(),
                    step.getCreatedAt(), step.getCompletedAt());
        }
    }
    public record AgentCheckpointView(String id, int sequence, AgentPhase phase, String stepId, boolean replayable, Instant createdAt) {
        public static AgentCheckpointView from(AgentCheckpoint checkpoint) {
            return new AgentCheckpointView(checkpoint.getId(), checkpoint.getCheckpointSequence(), checkpoint.getPhase(),
                    checkpoint.getStepId(), checkpoint.isReplayable(), checkpoint.getCreatedAt());
        }
    }
    public record AgentStepDetailView(String id, int index, AgentStepType type, AgentStepStatus status, long executionEpoch,
                                      String toolName, JsonNode input, JsonNode output, String summary, String errorCode,
                                      String errorMessage, Long durationMs, String finishReason, Integer inputTokens,
                                      Integer outputTokens, Integer totalTokens, String inputCheckpointId,
                                      String outputCheckpointId, Instant createdAt, Instant completedAt) {
        public static AgentStepDetailView from(KnowledgeRunStep step, JsonNode input, JsonNode output) {
            return new AgentStepDetailView(step.getId(), step.getStepIndex(), step.getStepType(), step.getStatus(),
                    step.getExecutionEpoch(), step.getToolName(), input, output, step.getSummaryText(), step.getErrorCode(),
                    step.getErrorMessage(), step.getDurationMs(), step.getFinishReason(), step.getInputTokens(),
                    step.getOutputTokens(), step.getTotalTokens(), step.getInputCheckpointId(), step.getOutputCheckpointId(),
                    step.getCreatedAt(), step.getCompletedAt());
        }
    }
    public static class StaleAgentExecutionException extends RuntimeException {
        public StaleAgentExecutionException() { super("Agent execution lease is no longer current"); }
    }
}
