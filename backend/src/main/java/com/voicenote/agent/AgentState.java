package com.voicenote.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.voicenote.provider.AgentModelClient;

import java.util.List;

/** Durable, observable Agent state. Hidden model reasoning is intentionally not represented. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentState(
        int schemaVersion,
        String runtimeVersion,
        AgentPhase phase,
        List<AgentModelClient.AgentMessage> messages,
        List<AgentModelClient.AgentToolCall> pendingToolCalls,
        String skillId,
        String skillVersion,
        String skillHash,
        String modelId,
        String promptVersion,
        String promptSnapshot,
        String skillSnapshot,
        List<DocumentSnapshot> documentSnapshots,
        CoverageState coverage,
        List<String> evidenceSourceRefs,
        int maxModelCalls,
        int modelCallsUsed,
        int maxAgentTurns,
        int agentTurnsUsed,
        int maxToolCalls,
        int toolCallsUsed,
        long maxActiveDurationMs,
        long activeDurationMs
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CURRENT_RUNTIME_VERSION = "react-runtime-v1";
    public static final String CURRENT_PROMPT_VERSION = "react-prompt-v1";

    public AgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        pendingToolCalls = pendingToolCalls == null ? List.of() : List.copyOf(pendingToolCalls);
        documentSnapshots = documentSnapshots == null ? List.of() : List.copyOf(documentSnapshots);
        coverage = coverage == null ? CoverageState.empty() : coverage;
        evidenceSourceRefs = evidenceSourceRefs == null ? List.of() : List.copyOf(evidenceSourceRefs);
    }

    public static AgentState initial(AgentPhase phase, String skillId, String skillVersion, String skillHash,
                                     List<AgentModelClient.AgentMessage> messages) {
        return new AgentState(CURRENT_SCHEMA_VERSION, CURRENT_RUNTIME_VERSION, phase, messages, List.of(),
                skillId, skillVersion, skillHash, null, CURRENT_PROMPT_VERSION, null, null, List.of(),
                CoverageState.empty(), List.of(), 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AgentState transition(AgentPhase nextPhase, List<AgentModelClient.AgentMessage> nextMessages,
                                 List<AgentModelClient.AgentToolCall> pending, CoverageState nextCoverage,
                                 List<String> sourceRefs) {
        return new AgentState(schemaVersion, runtimeVersion, nextPhase, nextMessages, pending, skillId, skillVersion,
                skillHash, modelId, promptVersion, promptSnapshot, skillSnapshot, documentSnapshots,
                nextCoverage, sourceRefs, maxModelCalls, modelCallsUsed, maxAgentTurns, agentTurnsUsed,
                maxToolCalls, toolCallsUsed, maxActiveDurationMs, activeDurationMs);
    }

    public AgentState withSkill(String id, String version, String hash) {
        return new AgentState(schemaVersion, runtimeVersion, phase, messages, pendingToolCalls, id, version, hash,
                modelId, promptVersion, promptSnapshot, skillSnapshot, documentSnapshots, coverage, evidenceSourceRefs,
                maxModelCalls, modelCallsUsed, maxAgentTurns, agentTurnsUsed, maxToolCalls, toolCallsUsed,
                maxActiveDurationMs, activeDurationMs);
    }

    public AgentState withFrozenContext(String frozenModelId, String frozenPrompt, String frozenSkill,
                                        List<DocumentSnapshot> frozenDocuments, int modelLimit, int turnLimit,
                                        int toolLimit, long durationLimitMs) {
        return new AgentState(schemaVersion, runtimeVersion, phase, messages, pendingToolCalls, skillId, skillVersion,
                skillHash, frozenModelId, CURRENT_PROMPT_VERSION, frozenPrompt, frozenSkill, frozenDocuments,
                coverage, evidenceSourceRefs, modelLimit, modelCallsUsed, turnLimit, agentTurnsUsed, toolLimit,
                toolCallsUsed, durationLimitMs, activeDurationMs);
    }

    public AgentState withRuntimeStats(int modelCalls, int turns, int toolCalls, long durationMs) {
        return new AgentState(schemaVersion, runtimeVersion, phase, messages, pendingToolCalls, skillId, skillVersion,
                skillHash, modelId, promptVersion, promptSnapshot, skillSnapshot, documentSnapshots,
                coverage, evidenceSourceRefs, maxModelCalls, modelCalls, maxAgentTurns, turns, maxToolCalls,
                toolCalls, maxActiveDurationMs, durationMs);
    }

    public record DocumentSnapshot(String transcriptionTaskId, String knowledgeDocumentId,
                                   String knowledgeIndexVersionId, String metadataSnapshot) { }

    public record CoverageState(List<String> overviewedDocumentIds, List<String> searchedDocumentIds,
                                List<String> limitations) {
        public CoverageState {
            overviewedDocumentIds = overviewedDocumentIds == null ? List.of() : List.copyOf(overviewedDocumentIds);
            searchedDocumentIds = searchedDocumentIds == null ? List.of() : List.copyOf(searchedDocumentIds);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }

        public static CoverageState empty() { return new CoverageState(List.of(), List.of(), List.of()); }
    }
}
