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
    private final AnalysisInvocationRepository invocations; private final AnalysisEvidenceRepository evidence;
    private final IdempotencyService idempotency; private final OutboxService outbox; private final ObjectMapper mapper; private final AppProperties properties;
    public AnalysisService(AnalysisRunRepository runs, TranscriptionTaskRepository tasks, TranscriptSegmentRepository segments, AnalysisInvocationRepository invocations, AnalysisEvidenceRepository evidence, IdempotencyService idempotency, OutboxService outbox, ObjectMapper mapper, AppProperties properties) {
        this.runs = runs; this.tasks = tasks; this.segments = segments; this.invocations = invocations; this.evidence = evidence; this.idempotency = idempotency; this.outbox = outbox; this.mapper = mapper; this.properties = properties;
    }
    @Transactional
    public AnalysisRun create(String ownerId, String key, CreateAnalysisCommand command) {
        TranscriptionTask task = tasks.findById(command.transcriptionTaskId()).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Transcription task was not found"));
        if (task.getStatus() != TaskStatus.SUCCEEDED) throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_READY", "Analysis requires a successful transcription");
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
    @Transactional public void markQueued(String runId) { runs.findById(runId).orElseThrow(); }
    @Transactional(readOnly = true) public AnalysisRun ownedRun(String ownerId, String runId) { return runs.findById(runId).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "Analysis run was not found")); }
    @Transactional(readOnly = true) public List<String> queuedRunIds() { return runs.findTop10ByStatusOrderByCreatedAtAsc(AnalysisRunStatus.QUEUED).stream().map(AnalysisRun::getId).toList(); }
    @Transactional public RunWork claim(String runId) {
        AnalysisRun run = runs.findById(runId).orElse(null); if (run == null || !run.start()) return null;
        TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElseThrow();
        return new RunWork(run.getId(), run.getAnalysisMode(), run.getCustomGoal(), chunk(segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion())));
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
        AnalysisRun run = runs.findById(runId).orElseThrow(); run.fail(failure.getCode() + ": " + failure.getMessage()); runs.save(run);
    }
    @Transactional public void completeRun(String runId, String result) {
        AnalysisRun run = runs.findById(runId).orElseThrow(); TranscriptionTask task = tasks.findById(run.getTranscriptionTaskId()).orElseThrow();
        List<TranscriptSegment> source = segments.findByTranscriptionTaskIdAndTranscriptVersionOrderBySegmentIndex(task.getId(), task.getTranscriptVersion());
        String normalized = normalizeAndPersistEvidence(run, source, result); run.succeed(normalized, "reviewed"); runs.save(run);
    }
    private AnalysisInvocation invocation(String runId, String stage, int index, String prompt) {
        return invocations.findByAnalysisRunIdAndStageNameAndChunkIndex(runId, stage, index)
                .orElseGet(() -> invocations.save(new AnalysisInvocation(runId, stage, index, Hashing.sha256(prompt))));
    }
    private String normalizeAndPersistEvidence(AnalysisRun run, List<TranscriptSegment> source, String raw) {
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (!parsed.isObject() || !parsed.path("findings").isArray()) {
                throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "ANALYSIS_SCHEMA_INVALID", "Analysis must return a JSON object with findings");
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
    public record AnalysisView(String id, AnalysisRunStatus status, int callsUsed, int maxCalls, String resultDocument) { public static AnalysisView from(AnalysisRun run) { return new AnalysisView(run.getId(), run.getStatus(), run.getCallsUsed(), run.getMaxCalls(), run.getResultDocument()); } }
    public record RunWork(String runId, String mode, String goal, List<String> chunks) { }
    public record StageAction(boolean cached, String value) { static StageAction cached(String response) { return new StageAction(true, response); } static StageAction call(String prompt) { return new StageAction(false, prompt); } }
    static List<String> chunk(List<TranscriptSegment> source) {
        List<String> output = new ArrayList<>(); StringBuilder current = new StringBuilder();
        for (TranscriptSegment segment : source) { String line = "[" + segment.getId() + "] " + segment.getStartMs() + "-" + segment.getEndMs() + "ms: " + segment.getTextContent() + "\n"; if (current.length() > 0 && current.length() + line.length() > 8000) { output.add(current.toString()); current = new StringBuilder(); } current.append(line); }
        if (!current.isEmpty()) output.add(current.toString()); return output;
    }
}
