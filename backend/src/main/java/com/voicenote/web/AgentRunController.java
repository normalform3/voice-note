package com.voicenote.web;

import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.service.KnowledgeAgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {
    private final KnowledgeAgentService runs;
    private final AppProperties properties;
    private final AgentSkillRegistry skills;
    private final KnowledgeRunEvidenceRepository evidence;
    private final KnowledgeDocumentRepository documents;
    private final TranscriptSegmentRepository segments;
    private final TranscriptSpeakerRepository speakers;
    private final KnowledgeChunkRepository chunks;

    public AgentRunController(KnowledgeAgentService runs, AppProperties properties, AgentSkillRegistry skills, KnowledgeRunEvidenceRepository evidence,
                              KnowledgeDocumentRepository documents, TranscriptSegmentRepository segments,
                              TranscriptSpeakerRepository speakers, KnowledgeChunkRepository chunks) {
        this.runs = runs; this.properties = properties; this.skills = skills; this.evidence = evidence; this.documents = documents;
        this.segments = segments; this.speakers = speakers; this.chunks = chunks;
    }

    @PostMapping
    KnowledgeAgentService.AgentRunView create(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody CreateAgentRunRequest request,
                                              Authentication authentication) {
        KnowledgeRun run = runs.createAgent(CurrentUser.require(authentication).id(), key,
                new KnowledgeAgentService.CreateAgentCommand(request.question(),
                        new KnowledgeAgentService.AgentScopeCommand(request.scope().type(), request.scope().transcriptionTaskIds()),
                        request.skillId(), request.timeZone()));
        return view(run);
    }

    @GetMapping
    List<KnowledgeAgentService.AgentRunView> list(Authentication authentication) {
        return runs.ownedRuns(CurrentUser.require(authentication).id()).stream().filter(value -> !value.isLegacy()).map(this::view).toList();
    }

    @GetMapping("/skills")
    List<SkillView> skills() {
        return skills.all().stream().map(SkillView::from).toList();
    }

    @GetMapping("/capabilities")
    Capabilities capabilities() {
        return new Capabilities(properties.getAgent().isEnabled(), properties.getKnowledge().isRerankEnabled(), properties.getMcp().isEnabled(),
                properties.getAgent().getMaxScopeDocuments(), properties.getAgent().getMaxModelCalls(), properties.getAgent().getMaxTurns(), properties.getAgent().getMaxToolCalls());
    }

    @GetMapping("/{runId}")
    AgentRunDetail get(@PathVariable String runId, Authentication authentication) {
        KnowledgeRun run = runs.ownedRun(CurrentUser.require(authentication).id(), runId);
        if (run.isLegacy()) throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent Run was not found");
        List<String> documentIds = runs.runDocuments(runId).stream().map(KnowledgeRunDocument::getTranscriptionTaskId).toList();
        return new AgentRunDetail(view(run), documentIds, runs.runSteps(runId).stream().map(KnowledgeAgentService.AgentStepView::from).toList(),
                evidence.findByKnowledgeRunId(runId).stream().map(this::evidenceView).toList());
    }

    private KnowledgeAgentService.AgentRunView view(KnowledgeRun run) {
        return KnowledgeAgentService.AgentRunView.from(run, runs.runDocuments(run.getId()).size());
    }

    private EvidenceView evidenceView(KnowledgeRunEvidence value) {
        String taskId = value.getTranscriptionTaskId();
        if (taskId == null && value.getKnowledgeDocumentId() != null) taskId = documents.findById(value.getKnowledgeDocumentId()).map(KnowledgeDocument::getTranscriptionTaskId).orElse(null);
        TranscriptSegment segment = value.getTranscriptSegmentId() == null ? null : segments.findById(value.getTranscriptSegmentId()).orElse(null);
        TranscriptSpeaker speaker = segment == null ? null : speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(
                segment.getTranscriptionTaskId(), segment.getTranscriptVersion(), segment.getEffectiveSpeakerId()).orElse(null);
        String topic = value.getKnowledgeChunkId() == null ? null : chunks.findById(value.getKnowledgeChunkId()).map(KnowledgeChunk::getTopicTitle).orElse(null);
        return new EvidenceView(value.getResultPath(), value.getSourceKind(), value.getSourceRef(), value.getKnowledgeDocumentId(), value.getKnowledgeChunkId(),
                taskId, value.getTranscriptSegmentId(), topic, segment == null ? null : segment.getEffectiveSpeakerId(),
                speaker == null ? null : speaker.getResolvedRole().name(), speaker == null ? null : speaker.getDisplayName(),
                segment == null ? null : segment.getStartMs(), segment == null ? null : segment.getEndMs(),
                segment == null ? null : segment.getTextContent(), value.getExternalLabel(), value.getExternalUrl());
    }

    public record AgentScopeRequest(@NotNull AgentScopeType type, @Size(max = 50) List<String> transcriptionTaskIds) { }
    public record CreateAgentRunRequest(@NotBlank @Size(max = 8000) String question, @NotNull @Valid AgentScopeRequest scope,
                                        String skillId, @NotBlank String timeZone) { }
    public record AgentRunDetail(KnowledgeAgentService.AgentRunView run, List<String> documentIds, List<KnowledgeAgentService.AgentStepView> steps,
                                 List<EvidenceView> evidence) { }
    public record EvidenceView(String resultPath, EvidenceSourceKind sourceKind, String sourceRef, String documentId, String chunkId,
                               String transcriptionTaskId, String segmentId, String topic, String speakerId, String role, String speaker,
                               Long startMs, Long endMs, String text, String externalLabel, String externalUrl) { }
    public record SkillView(String id, String version, String displayName, String description) {
        static SkillView from(AgentSkill value) { return new SkillView(value.id(), value.version(), value.displayName(), value.description()); }
    }
    public record Capabilities(boolean enabled, boolean rerankEnabled, boolean mcpEnabled, int maxScopeDocuments,
                               int maxModelCalls, int maxTurns, int maxToolCalls) { }
}
