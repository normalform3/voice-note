package com.voicenote.web;

import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.VoiceTtsService;
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
    private final KnowledgeRunSourceRepository sources;
    private final VoiceTtsService tts;

    public AgentRunController(KnowledgeAgentService runs, AppProperties properties, AgentSkillRegistry skills, KnowledgeRunEvidenceRepository evidence,
                              KnowledgeDocumentRepository documents, TranscriptSegmentRepository segments,
                              TranscriptSpeakerRepository speakers, KnowledgeChunkRepository chunks, KnowledgeRunSourceRepository sources) {
        this.runs = runs; this.properties = properties; this.skills = skills; this.evidence = evidence; this.documents = documents;
        this.segments = segments; this.speakers = speakers; this.chunks = chunks; this.sources = sources;
        this.tts = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunController(KnowledgeAgentService runs, AppProperties properties, AgentSkillRegistry skills, KnowledgeRunEvidenceRepository evidence,
                              KnowledgeDocumentRepository documents, TranscriptSegmentRepository segments,
                              TranscriptSpeakerRepository speakers, KnowledgeChunkRepository chunks, KnowledgeRunSourceRepository sources,
                              VoiceTtsService tts) {
        this.runs = runs; this.properties = properties; this.skills = skills; this.evidence = evidence; this.documents = documents;
        this.segments = segments; this.speakers = speakers; this.chunks = chunks; this.sources = sources; this.tts = tts;
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
    List<SkillView> skills(Authentication authentication) {
        return skills.all(CurrentUser.require(authentication).id()).stream().map(SkillView::from).toList();
    }

    @GetMapping("/capabilities")
    Capabilities capabilities() {
        return new Capabilities(properties.getAgent().isEnabled(), tts != null && tts.isEnabled(), properties.getKnowledge().isRerankEnabled(), properties.getMcp().isEnabled(),
                properties.getMemory().isEnabled(), properties.getMemory().getMaxPendingCandidates(), properties.getMemory().getMaxActiveMemories(),
                properties.getMemory().getRecentTurns(), properties.getMemory().getContextMaxCharacters(),
                properties.getMemory().getSummaryMaxCharacters(), properties.getMemory().getSearchLimit(),
                properties.getAgent().getMaxScopeDocuments(), properties.getAgent().getMaxModelCalls(), properties.getAgent().getMaxTurns(), properties.getAgent().getMaxToolCalls());
    }

    @GetMapping("/{runId}")
    AgentRunDetail get(@PathVariable String runId, Authentication authentication) {
        KnowledgeRun run = runs.ownedRun(CurrentUser.require(authentication).id(), runId);
        if (run.isLegacy()) throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_RUN_NOT_FOUND", "Agent Run was not found");
        List<String> documentIds = runs.runDocuments(runId).stream().map(KnowledgeRunDocument::getTranscriptionTaskId).toList();
        return new AgentRunDetail(view(run), documentIds, runs.childRunIds(run.getOwnerId(), runId), runs.stepViews(runId), runs.checkpointViews(runId),
                evidence.findByKnowledgeRunId(runId).stream().map(this::evidenceView).toList());
    }

    @GetMapping("/{runId}/steps/{stepId}")
    KnowledgeAgentService.AgentStepDetailView step(@PathVariable String runId, @PathVariable String stepId,
                                                   Authentication authentication) {
        return runs.stepDetail(CurrentUser.require(authentication).id(), runId, stepId);
    }

    @PostMapping("/{runId}/replays")
    KnowledgeAgentService.AgentRunView replay(@PathVariable String runId,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody ReplayAgentRunRequest request,
                                              Authentication authentication) {
        KnowledgeRun replay = runs.replayAgent(CurrentUser.require(authentication).id(), key, runId, request.checkpointId());
        return view(replay);
    }

    private KnowledgeAgentService.AgentRunView view(KnowledgeRun run) {
        return KnowledgeAgentService.AgentRunView.from(run, runs.runDocuments(run.getId()).size(), runs.skillDisplayName(run));
    }

    private EvidenceView evidenceView(KnowledgeRunEvidence value) {
        String taskId = value.getTranscriptionTaskId();
        if (taskId == null && value.getKnowledgeDocumentId() != null) taskId = documents.findById(value.getKnowledgeDocumentId()).map(KnowledgeDocument::getTranscriptionTaskId).orElse(null);
        TranscriptSegment segment = value.getTranscriptSegmentId() == null ? null : segments.findById(value.getTranscriptSegmentId()).orElse(null);
        TranscriptSpeaker speaker = segment == null ? null : speakers.findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(
                segment.getTranscriptionTaskId(), segment.getTranscriptVersion(), segment.getEffectiveSpeakerId()).orElse(null);
        String topic = value.getKnowledgeChunkId() == null ? null : chunks.findById(value.getKnowledgeChunkId()).map(KnowledgeChunk::getTopicTitle).orElse(null);
        String memoryText = value.getUserMemoryContentSnapshot();
        if (memoryText == null && value.getSourceKind() == EvidenceSourceKind.USER_MEMORY && value.getSourceRef() != null) {
            memoryText = sources.findByKnowledgeRunIdAndSourceRef(value.getKnowledgeRunId(), value.getSourceRef())
                    .map(source -> source.toEvidenceSource().text()).orElse(null);
        }
        return new EvidenceView(value.getResultPath(), value.getSourceKind(), value.getSourceRef(), value.getKnowledgeDocumentId(), value.getKnowledgeChunkId(),
                taskId, value.getTranscriptSegmentId(), topic, segment == null ? null : segment.getEffectiveSpeakerId(),
                speaker == null ? null : speaker.getResolvedRole().name(), speaker == null ? null : speaker.getDisplayName(),
                segment == null ? null : segment.getStartMs(), segment == null ? null : segment.getEndMs(),
                segment == null ? memoryText : segment.getTextContent(), value.getUserMemoryId(), value.getUserMemoryVersionId(),
                value.getExternalLabel(), value.getExternalUrl());
    }

    public record AgentScopeRequest(@NotNull AgentScopeType type, @Size(max = 50) List<String> transcriptionTaskIds) { }
    public record CreateAgentRunRequest(@NotBlank @Size(max = 8000) String question, @NotNull @Valid AgentScopeRequest scope,
                                        String skillId, @NotBlank String timeZone) { }
    public record ReplayAgentRunRequest(@NotBlank String checkpointId) { }
    public record AgentRunDetail(KnowledgeAgentService.AgentRunView run, List<String> documentIds, List<String> childRunIds, List<KnowledgeAgentService.AgentStepView> steps,
                                 List<KnowledgeAgentService.AgentCheckpointView> checkpoints, List<EvidenceView> evidence) { }
    public record EvidenceView(String resultPath, EvidenceSourceKind sourceKind, String sourceRef, String documentId, String chunkId,
                               String transcriptionTaskId, String segmentId, String topic, String speakerId, String role, String speaker,
                               Long startMs, Long endMs, String text, String memoryId, String memoryVersionId,
                               String externalLabel, String externalUrl) { }
    public record SkillView(String id, String version, String displayName, String description, SkillSource source,
                            SkillInvocationPolicy invocationPolicy, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes,
                            List<SkillBlockType> outputBlocks, String defaultPrompt, List<String> suggestedPrompts) {
        static SkillView from(AgentSkill value) { return new SkillView(value.id(), value.version(), value.displayName(), value.description(),
                value.source(), value.invocationPolicy(), value.sceneTypes(), value.scopeTypes(), value.outputBlocks(), value.defaultPrompt(), value.routingExamples()); }
    }
    public record Capabilities(boolean enabled, boolean ttsEnabled, boolean rerankEnabled, boolean mcpEnabled, boolean memoryEnabled,
                               int maxPendingMemoryCandidates, int maxActiveMemories,
                               int recentConversationTurns, int conversationContextMaxCharacters,
                               int conversationSummaryMaxCharacters, int memorySearchLimit,
                               int maxScopeDocuments, int maxModelCalls, int maxTurns, int maxToolCalls) { }
}
