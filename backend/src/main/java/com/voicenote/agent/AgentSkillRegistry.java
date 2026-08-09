package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.service.Hashing;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent Skill catalog plus immutable built-in package synchronization. */
@Component
public class AgentSkillRegistry {
    private static final List<String> BUILT_INS = List.of("knowledge-qa", "meeting-summary", "interview-retro");
    private final ObjectMapper mapper;
    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;

    public AgentSkillRegistry(ObjectMapper mapper, SkillDefinitionRepository definitions, SkillVersionRepository versions,
                              SkillResourceRepository resources) {
        this.mapper = mapper; this.definitions = definitions; this.versions = versions; this.resources = resources;
    }

    @PostConstruct
    void synchronizeBuiltIns() {
        try {
            for (String packageName : BUILT_INS) synchronizeBuiltIn(packageName);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot synchronize built-in Agent Skills", exception);
        }
    }

    private void synchronizeBuiltIn(String packageName) throws Exception {
        String root = "agent-skills/" + packageName + "/";
        BuiltInManifest manifest = mapper.readValue(new ClassPathResource(root + "manifest.json").getInputStream(), BuiltInManifest.class);
        if (!packageName.equals(manifest.id()) || manifest.version() == null || !manifest.version().matches("v[1-9][0-9]*")) {
            throw new IllegalStateException("Invalid built-in Skill manifest: " + packageName);
        }
        String instructions = read(root + "instructions.md");
        List<BuiltInResource> packageResources = new ArrayList<>();
        for (ResourceManifest item : manifest.resources() == null ? List.<ResourceManifest>of() : manifest.resources()) {
            packageResources.add(new BuiltInResource(item.type(), item.path(), item.name(), item.purpose(), read(root + item.path())));
        }
        String contentHash = Hashing.canonicalJsonHash(Map.of(
                "manifest", manifest, "instructions", instructions, "resources", packageResources));
        SkillDefinition definition = definitions.findById(manifest.id()).orElseGet(() -> SkillDefinition.builtIn(
                manifest.id(), manifest.displayName(), manifest.description(), json(manifest.sceneTypes()), json(manifest.scopeTypes())));
        if (definition.getSource() != SkillSource.BUILTIN || definition.getOwnerId() != null) {
            throw new IllegalStateException("Built-in Skill id is already owned by another source: " + manifest.id());
        }
        definition.updateMetadata(manifest.displayName(), manifest.description(), json(manifest.sceneTypes()), json(manifest.scopeTypes()));
        SkillVersion version = versions.findBySkillDefinitionIdAndVersionName(manifest.id(), manifest.version()).orElse(null);
        if (version != null && !version.getContentHash().equals(contentHash)) {
            throw new IllegalStateException("Built-in Skill " + manifest.id() + " changed without a version bump");
        }
        if (version != null) {
            Map<String, String> expectedResourceHashes = new LinkedHashMap<>();
            packageResources.forEach(value -> expectedResourceHashes.put(value.path(), Hashing.sha256(value.content())));
            Map<String, String> storedResourceHashes = new LinkedHashMap<>();
            resources.findBySkillVersionIdOrderBySortOrderAsc(version.getId()).forEach(value -> storedResourceHashes.put(value.getResourceKey(), value.getContentHash()));
            if (!storedResourceHashes.equals(expectedResourceHashes)) throw new IllegalStateException("Stored resources for built-in Skill " + manifest.id() + " do not match its immutable package");
        }
        if (version == null) {
            definitions.save(definition);
            int versionNumber = Integer.parseInt(manifest.version().substring(1));
            version = versions.save(new SkillVersion(manifest.id(), versionNumber, manifest.version(), instructions,
                    json(manifest.allowedTools()), json(manifest.outputBlocks()), json(manifest.shouldTrigger()),
                    json(manifest.shouldNotTrigger()), manifest.defaultPrompt(), contentHash));
            int index = 0;
            for (BuiltInResource item : packageResources) resources.save(new SkillResource(version.getId(), item.path(), item.type(),
                    item.name(), item.purpose(), item.content(), Hashing.sha256(item.content()), index++));
            version.publish();
            version.markTriggerPreview(true, contentHash);
            versions.save(version);
        }
        definition.publishBuiltIn(version.getId());
        definitions.save(definition);
    }

    public AgentSkill require(String ownerId, String id) {
        SkillDefinition definition = definitions.findById(id).filter(value -> visibleTo(value, ownerId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent skill: " + id));
        if (definition.getStatus() == SkillStatus.ARCHIVED || definition.getPublishedVersionId() == null) {
            throw new IllegalArgumentException("Agent skill is not published: " + id);
        }
        return toRuntime(definition, versions.findById(definition.getPublishedVersionId()).orElseThrow());
    }

    /** Compatibility overload for built-in-only callers. */
    public AgentSkill require(String id) { return require(null, id); }
    public AgentSkill fallback() { return require(null, "knowledge-qa"); }
    public List<AgentSkill> all() { return all(null); }

    public List<AgentSkill> all(String ownerId) {
        List<SkillDefinition> visible = new ArrayList<>(definitions.findBySourceAndStatusNotOrderByUpdatedAtDesc(SkillSource.BUILTIN, SkillStatus.ARCHIVED));
        if (ownerId != null) visible.addAll(definitions.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(ownerId, SkillStatus.ARCHIVED));
        return visible.stream().filter(value -> value.getPublishedVersionId() != null)
                .map(value -> toRuntime(value, versions.findById(value.getPublishedVersionId()).orElseThrow())).toList();
    }

    public List<AgentSkill> automaticCandidates(String ownerId, AgentScopeType scopeType, Collection<SceneType> sceneTypes) {
        return all(ownerId).stream().filter(value -> value.invocationPolicy() == SkillInvocationPolicy.AUTO)
                .filter(value -> compatible(value, scopeType, sceneTypes)).toList();
    }

    public boolean compatible(AgentSkill skill, AgentScopeType scopeType, Collection<SceneType> sceneTypes) {
        if (!skill.scopeTypes().contains(scopeType)) return false;
        return sceneTypes == null || sceneTypes.isEmpty() || sceneTypes.stream().anyMatch(skill.sceneTypes()::contains);
    }

    private boolean visibleTo(SkillDefinition value, String ownerId) {
        return value.getSource() == SkillSource.BUILTIN || Objects.equals(value.getOwnerId(), ownerId);
    }

    private AgentSkill toRuntime(SkillDefinition definition, SkillVersion version) {
        List<SkillResource> resourceValues = resources.findBySkillVersionIdOrderBySortOrderAsc(version.getId());
        return new AgentSkill(definition.getId(), version.getVersionName(), definition.getDisplayName(), definition.getDescription(),
                list(version.getShouldTrigger(), String.class), version.getInstructions(), list(version.getAllowedTools(), String.class), true,
                version.getId(), definition.getSource(), definition.getInvocationPolicy(),
                list(definition.getSceneTypes(), SceneType.class), list(definition.getScopeTypes(), AgentScopeType.class),
                list(version.getOutputBlocks(), SkillBlockType.class), resourceValues.stream().map(value -> new AgentSkill.ResourceDescriptor(
                        value.getId(), value.getResourceKey(), value.getResourceType(), value.getName(), value.getPurpose(),
                        value.getMarkdownContent().getBytes(StandardCharsets.UTF_8).length)).toList(), version.getDefaultPrompt(),
                list(version.getShouldNotTrigger(), String.class));
    }

    private String read(String path) throws Exception {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(Objects.requireNonNullElse(value, List.of())); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize Skill catalog data", exception); }
    }
    private <T> List<T> list(String value, Class<T> type) {
        try { return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, type)); }
        catch (Exception exception) { throw new IllegalStateException("Stored Skill list is invalid", exception); }
    }

    private record BuiltInManifest(String id, String version, String displayName, String description,
                                   SkillInvocationPolicy invocationPolicy, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes,
                                   List<String> allowedTools, List<SkillBlockType> outputBlocks,
                                   boolean requireOverviewForMultipleDocuments, List<String> shouldTrigger,
                                   List<String> shouldNotTrigger, String defaultPrompt, List<ResourceManifest> resources) { }
    private record ResourceManifest(SkillResourceType type, String path, String name, String purpose) { }
    private record BuiltInResource(SkillResourceType type, String path, String name, String purpose, String content) { }
}
