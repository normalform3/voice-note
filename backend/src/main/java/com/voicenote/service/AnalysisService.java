package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AnalysisService {
    private static final String CREATE_OPERATION = "CREATE_ANALYSIS_RUN";
    private final AnalysisRunRepository runs; private final TranscriptionTaskRepository tasks; private final TranscriptSegmentRepository segments;
    private final OrganizedDocumentRepository organizedDocuments; private final OrganizedDocumentBlockRepository organizedBlocks;
    private final AnalysisInvocationRepository invocations; private final AnalysisEvidenceRepository evidence;
    private final IdempotencyService idempotency; private final OutboxService outbox; private final ObjectMapper mapper; private final AppProperties properties;
    private final ProgressEventPublisher progressEvents;
    public AnalysisService(AnalysisRunRepository runs, TranscriptionTaskRepository tasks, TranscriptSegmentRepository segments,
                           OrganizedDocumentRepository organizedDocuments, OrganizedDocumentBlockRepository organizedBlocks,
                           AnalysisInvocationRepository invocations, AnalysisEvidenceRepository evidence, IdempotencyService idempotency,
                           OutboxService outbox, ObjectMapper mapper, AppProperties properties, ProgressEventPublisher progressEvents) {
        this.runs = runs; this.tasks = tasks; this.segments = segments; this.organizedDocuments = organizedDocuments; this.organizedBlocks = organizedBlocks;
        this.invocations = invocations; this.evidence = evidence; this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties; this.progressEvents = progressEvents;
    }
    @Transactional
    public AnalysisRun create(String ownerId, String key, CreateAnalysisCommand command) {
        TranscriptionTask task = tasks.findById(command.transcriptionTaskId()).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (!task.isTranscriptReady()) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Analysis requires a persisted transcription");
        List<TranscriptSegment> source = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion());
        String snapshotHash = Hashing.canonicalJsonHash(source.stream().map(segment -> Map.of("id", segment.getId(), "text", segment.getTextContent(), "start", segment.getStartMs(), "end", segment.getEndMs())).toList());
        String mode = command.mode() == null || command.mode().isBlank() ? "custom" : command.mode().trim().toLowerCase();
        String goal = command.goal().trim();
        String semanticHash = Hashing.canonicalJsonHash(Map.of("snapshot", snapshotHash, "mode", mode, "goal", goal, "template", "analysis-v1", "model", properties.getDashscope().getChatModel()));
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, Hashing.canonicalJsonHash(command));
        if (record.getResourceId() != null) return runs.findById(record.getResourceId()).orElseThrow();
        int chunkCount = chunk(source).size();
        AnalysisRun run = runs.findByOwnerIdAndTranscriptionTaskIdAndSemanticHash(ownerId, task.getId(), semanticHash)
                .orElseGet(() -> { AnalysisRun created = runs.save(new AnalysisRun(ownerId, task.getId(), snapshotHash, mode, goal, "analysis-v1", properties.getDashscope().getChatModel(), semanticHash, chunkCount + 3)); outbox.enqueue("analysis_run", created.getId(), EventType.ANALYSIS_REQUESTED); return created; });
        try { idempotency.complete(record, run.getId(), mapper.writeValueAsString(AnalysisView.from(run))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent analysis response", exception); }
        return run;
    }
    @Transactional
    public AnalysisRun createSummary(String ownerId, String key, String organizedDocumentId) {
        OrganizedDocument document = organizedDocuments.findById(organizedDocumentId).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZED_DOCUMENT_NOT_FOUND", "Organized document was not found"));
        if (document.getStatus() != OrganizedDocumentStatus.READY) throw new ApiException(HttpStatus.CONFLICT, "ORGANIZED_DOCUMENT_NOT_READY", "Summary requires a completed organized document");
        TranscriptionTask task = tasks.findById(document.getTranscriptionTaskId()).orElseThrow();
        List<OrganizedDocumentBlock> source = organizedBlocks.findByOrganizedDocumentIdOrderByBlockIndex(document.getId());
        String snapshotHash = Hashing.canonicalJsonHash(source.stream().map(block -> Map.of("id", block.getId(), "source", block.getSourceSegmentIds(), "text", block.getTextContent())).toList());
        String goal = "Summarize the organized document faithfully. Include concise findings and cite the source segmentIds for every finding.";
        String semanticHash = Hashing.canonicalJsonHash(Map.of("snapshot", snapshotHash, "mode", "summary", "goal", goal, "template", "organized-summary-v1", "model", properties.getDashscope().getChatModel()));
        IdempotencyRecord record = idempotency.reserve(ownerId, CREATE_OPERATION, key, Hashing.canonicalJsonHash(Map.of("organizedDocumentId", organizedDocumentId, "mode", "summary")));
        if (record.getResourceId() != null) return runs.findById(record.getResourceId()).orElseThrow();
        List<String> chunks = chunkOrganized(source);
        AnalysisRun run = runs.findByOwnerIdAndTranscriptionTaskIdAndSemanticHash(ownerId, task.getId(), semanticHash)
                .orElseGet(() -> {
                    AnalysisRun created = new AnalysisRun(ownerId, task.getId(), snapshotHash, "summary", goal, "organized-summary-v1", properties.getDashscope().getChatModel(), semanticHash, chunks.size() + 3);
                    created.useOrganizedDocument(document.getId()); created = runs.save(created);
                    outbox.enqueue("analysis_run", created.getId(), EventType.ANALYSIS_REQUESTED);
                    return created;
                });
        try { idempotency.complete(record, run.getId(), mapper.writeValueAsString(AnalysisView.from(run))); }
        catch (Exception exception) { throw new IllegalStateException("Cannot persist idempotent summary response", exception); }
        return run;
    }
    @Transactional public void markQueued(String runId) { runs.findById(runId).orElseThrow(); }
    @Transactional(readOnly = true) public AnalysisRun ownedRun(String ownerId, String runId) { return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis run was not found")); }
    @Transactional(readOnly = true) public List<AnalysisRun> ownedRuns(String ownerId) { return runs.findByOwnerIdOrderByCreatedAtDesc(ownerId); }
    @Transactional(readOnly = true) public List<String> queuedRunIds() { return runs.findTop10ByStatusOrderByCreatedAtAsc(AnalysisRunStatus.QUEUED).stream().map(AnalysisRun::getId).toList(); }
    @Transactional public RunWork claim(String runId) {
        AnalysisRun run = runs.findById(runId).orElse(null); if (run == null || !run.start()) return null;
        TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElseThrow();
        List<String> chunks = run.getOrganizedDocumentId() == null
                ? chunk(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion()))
                : chunkOrganized(organizedBlocks.findByOrganizedDocumentIdOrderByBlockIndex(run.getOrganizedDocumentId()));
        return new RunWork(run.getId(), run.getAnalysisMode(), run.getCustomGoal(), chunks);
    }
    @Transactional public StageAction prepareStage(String runId, String stage, int chunkIndex, String prompt) {
        AnalysisRun run = runs.findById(runId).orElseThrow();
        AnalysisInvocation invocation = invocation(runId, stage, chunkIndex, prompt);
        if (invocation.getStatus() == InvocationStatus.SUCCEEDED) return StageAction.cached(invocation.getResponseDocument());
        if (invocation.getStatus() == InvocationStatus.UNKNOWN) throw new ProviderException(ProviderException.Kind.AMBIGUOUS_SUBMISSION, "ANALYSIS_CALL_UNKNOWN", "A prior model call has an unknown outcome; create a new analysis run to retry");
        if (!run.consumeCall()) { runs.save(run); throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_BUDGET_EXHAUSTED", "Analysis call budget exhausted"); }
        invocation.markInFlight(); invocations.save(invocation); runs.save(run); return StageAction.call(prompt);
    }
    @Transactional public void completeStage(String runId, String stage, int index, String response) { AnalysisInvocation invocation = invocations.findByAnalysisRunIdAndStageNameAndChunkIndex(runId, stage, index).orElseThrow(); invocation.markSucceeded(response); invocations.save(invocation); }
    @Transactional public void failRun(String runId, ProviderException failure) {
        AnalysisRun run = runs.findById(runId).orElseThrow(); run.fail(failure.getCode() + ": " + failure.getMessage()); runs.save(run); notifySettled(run);
    }
    @Transactional public void completeRun(String runId, String result) {
        AnalysisRun run = runs.findById(runId).orElseThrow(); TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElseThrow();
        List<TranscriptSegment> source = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion());
        String normalized = normalizeAndPersistEvidence(run, source, result); run.succeed(normalized, "reviewed"); runs.save(run); notifySettled(run);
    }
    private AnalysisInvocation invocation(String runId, String stage, int index, String prompt) {
        return invocations.findByAnalysisRunIdAndStageNameAndChunkIndex(runId, stage, index)
                .orElseGet(() -> invocations.save(new AnalysisInvocation(runId, stage, index, Hashing.sha256(prompt))));
    }
    private void notifySettled(AnalysisRun run) {
        if (properties.getRocketmq().isEnabled()) outbox.enqueue("analysis_run", run.getId(), EventType.PROGRESS_CHANGED);
        else progressEvents.publish(new ProgressEventPublisher.ProgressNotification(run.getOwnerId(), "analysis-run-settled", run.getId()));
    }
    private String normalizeAndPersistEvidence(AnalysisRun run, List<TranscriptSegment> source, String raw) {
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (!parsed.isObject() || !parsed.path("answer").isTextual() || !parsed.path("findings").isArray()) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_SCHEMA_INVALID", "Analysis must return an answer and findings JSON object");
            }
            Set<String> validIds = new HashSet<>(); for (TranscriptSegment segment : source) validIds.add(segment.getId());
            int evidenceCount = 0;
            for (int findingIndex = 0; findingIndex < parsed.path("findings").size(); findingIndex++) {
                JsonNode finding = parsed.path("findings").get(findingIndex);
                JsonNode citations = finding.path("evidence");
                if (!citations.isArray()) continue;
                for (JsonNode citation : citations) {
                    String segmentId = citation.path("segmentId").asText(null);
                    if (segmentId == null || !validIds.contains(segmentId)) {
                        throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "INVALID_EVIDENCE", "Analysis cited a segment outside the transcript snapshot");
                    }
                    Integer start = citation.has("startOffset") ? citation.path("startOffset").asInt() : null;
                    Integer end = citation.has("endOffset") ? citation.path("endOffset").asInt() : null;
                    evidence.save(new AnalysisEvidence(run.getId(), "/findings/" + findingIndex, segmentId, start, end)); evidenceCount++;
                }
            }
            if (evidenceCount == 0) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "MISSING_EVIDENCE", "Analysis returned no evidence citations");
            return mapper.writeValueAsString(parsed);
        } catch (ProviderException exception) { throw exception; }
        catch (Exception exception) { throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_SCHEMA_INVALID", "Analysis did not return valid JSON"); }
    }
    public record CreateAnalysisCommand(String transcriptionTaskId, String mode, String goal) { }
    public record AnalysisView(String id, String transcriptionTaskId, AnalysisRunStatus status, int callsUsed, int maxCalls, String resultDocument, String failureMessage) { public static AnalysisView from(AnalysisRun run) { return new AnalysisView(run.getId(), run.getTranscriptionTaskId(), run.getStatus(), run.getCallsUsed(), run.getMaxCalls(), run.getResultDocument(), run.getFailureMessage()); } }
    public record RunWork(String runId, String mode, String goal, List<String> chunks) { }
    public record StageAction(boolean cached, String value) { static StageAction cached(String response) { return new StageAction(true, response); } static StageAction call(String prompt) { return new StageAction(false, prompt); } }
    static List<String> chunk(List<TranscriptSegment> source) {
        List<String> output = new ArrayList<>(); StringBuilder current = new StringBuilder();
        for (TranscriptSegment segment : source) { String line = "[" + segment.getId() + "] " + segment.getStartMs() + "-" + segment.getEndMs() + "ms: " + segment.getTextContent() + "\n"; if (current.length() > 0 && current.length() + line.length() > 8000) { output.add(current.toString()); current = new StringBuilder(); } current.append(line); }
        if (!current.isEmpty()) output.add(current.toString()); return output;
    }
    private List<String> chunkOrganized(List<OrganizedDocumentBlock> source) {
        List<String> output = new ArrayList<>(); StringBuilder current = new StringBuilder();
        for (OrganizedDocumentBlock block : source) {
            String line = "[source=" + block.getSourceSegmentIds() + "] " + (block.getTopicTitle() == null ? "整理片段" : block.getTopicTitle()) + "\n" + block.getTextContent() + "\n";
            if (!current.isEmpty() && current.length() + line.length() > 8000) { output.add(current.toString()); current = new StringBuilder(); }
            current.append(line);
        }
        if (!current.isEmpty()) output.add(current.toString()); return output;
    }
}
