package com.voicenote.agent;

import com.voicenote.domain.*;
import java.util.List;

public record AgentSkill(String id, String version, String displayName, String description, List<String> routingExamples,
                         String instructions, List<String> allowedTools, boolean requireOverviewForMultipleDocuments,
                         String versionId, SkillSource source, SkillInvocationPolicy invocationPolicy,
                         List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes, List<SkillBlockType> outputBlocks,
                         List<ResourceDescriptor> resources, String defaultPrompt, List<String> negativeRoutingExamples) {
    public AgentSkill {
        routingExamples = routingExamples == null ? List.of() : List.copyOf(routingExamples);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        sceneTypes = sceneTypes == null || sceneTypes.isEmpty() ? List.of(SceneType.values()) : List.copyOf(sceneTypes);
        scopeTypes = scopeTypes == null || scopeTypes.isEmpty() ? List.of(AgentScopeType.values()) : List.copyOf(scopeTypes);
        outputBlocks = outputBlocks == null ? List.of() : List.copyOf(outputBlocks);
        resources = resources == null ? List.of() : List.copyOf(resources);
        negativeRoutingExamples = negativeRoutingExamples == null ? List.of() : List.copyOf(negativeRoutingExamples);
        source = source == null ? SkillSource.BUILTIN : source;
        invocationPolicy = invocationPolicy == null ? SkillInvocationPolicy.AUTO : invocationPolicy;
        versionId = versionId == null ? id + ":" + version : versionId;
    }

    /** Keeps old JSON snapshots and focused tests source-compatible. */
    public AgentSkill(String id, String version, String displayName, String description, List<String> routingExamples,
                      String instructions, List<String> allowedTools, boolean requireOverviewForMultipleDocuments) {
        this(id, version, displayName, description, routingExamples, instructions, allowedTools,
                requireOverviewForMultipleDocuments, null, SkillSource.BUILTIN, SkillInvocationPolicy.AUTO,
                List.of(SceneType.values()), List.of(AgentScopeType.values()), List.of(), List.of(), null, List.of());
    }

    public record ResourceDescriptor(String id, String key, SkillResourceType type, String name, String purpose, int sizeBytes) { }
}
