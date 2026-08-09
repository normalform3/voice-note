package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.provider.ProviderException;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class SkillService {
    public static final int MAX_INSTRUCTIONS = 12_000;
    public static final int MAX_RESOURCES = 10;
    public static final int MAX_RESOURCE_BYTES = 50 * 1024;
    public static final int MAX_TOTAL_RESOURCE_BYTES = 200 * 1024;
    public static final Set<String> USER_GRANTABLE_TOOLS = Set.of(
            "document_list", "document_overview", "knowledge_search", "transcript_context", "skill_resource_read", "finalize_answer");
    private static final List<SkillBlockType> DEFAULT_BLOCKS = List.of(SkillBlockType.SUMMARY, SkillBlockType.FINDINGS);

    private final SkillDefinitionRepository definitions;
    private final SkillVersionRepository versions;
    private final SkillResourceRepository resources;
    private final KnowledgeRunRepository runs;
    private final AgentModelClient model;
    private final ObjectMapper mapper;

    public SkillService(SkillDefinitionRepository definitions, SkillVersionRepository versions, SkillResourceRepository resources,
                        KnowledgeRunRepository runs, AgentModelClient model, ObjectMapper mapper) {
        this.definitions = definitions; this.versions = versions; this.resources = resources;
        this.runs = runs; this.model = model; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SkillSummary> list(String ownerId) {
        List<SkillDefinition> visible = new ArrayList<>(definitions.findBySourceAndStatusNotOrderByUpdatedAtDesc(SkillSource.BUILTIN, SkillStatus.ARCHIVED));
        visible.addAll(definitions.findByOwnerIdAndStatusNotOrderByUpdatedAtDesc(ownerId, SkillStatus.ARCHIVED));
        return visible.stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public SkillDetail get(String ownerId, String id) { return detail(visible(ownerId, id)); }

    @Transactional
    public SkillDetail create(String ownerId, CreateCommand command) {
        String displayName = required(command.displayName(), 120, "Skill name");
        String description = required(command.description(), 1000, "Skill description");
        SkillDefinition definition = SkillDefinition.user(ownerId, displayName, description,
                json(normalizeScenes(command.sceneTypes())), json(normalizeScopes(command.scopeTypes())));
        definitions.save(definition);
        SkillVersion version = new SkillVersion(definition.getId(), 1, "v1", "请描述这个 Skill 的工作流、证据要求和停止条件。",
                json(USER_GRANTABLE_TOOLS), json(DEFAULT_BLOCKS), "[]", "[]", null, "pending");
        version.updateDraft(version.getInstructions(), version.getAllowedTools(), version.getOutputBlocks(), "[]", "[]", null,
                contentHash(definition, version.getInstructions(), list(version.getAllowedTools(), String.class), DEFAULT_BLOCKS, List.of(), List.of(), null, List.of()));
        versions.save(version); definition.setDraftVersion(version.getId()); definitions.save(definition);
        return detail(definition);
    }

    @Transactional
    public SkillDetail updateDraft(String ownerId, String id, DraftCommand command) {
        SkillDefinition definition = ownedUser(ownerId, id);
        if (definition.getStatus() == SkillStatus.ARCHIVED) throw conflict("SKILL_ARCHIVED", "Archived Skills cannot be edited");
        String displayName = required(command.displayName(), 120, "Skill name");
        String description = required(command.description(), 1000, "Skill description");
        String instructions = required(command.instructions(), MAX_INSTRUCTIONS, "Skill instructions");
        List<String> allowedTools = sanitizeTools(command.allowedTools());
        List<SkillBlockType> outputBlocks = normalizeBlocks(command.outputBlocks());
        List<String> positives = normalizeExamples(command.shouldTrigger());
        List<String> negatives = normalizeExamples(command.shouldNotTrigger());
        List<ResourceInput> resourceInputs = validateResources(command.resources());
        SkillVersion draft = ensureDraft(definition);
        definition.updateMetadata(displayName, description, json(normalizeScenes(command.sceneTypes())), json(normalizeScopes(command.scopeTypes())));
        String hash = contentHash(definition, instructions, allowedTools, outputBlocks, positives, negatives, command.defaultPrompt(), resourceInputs);
        draft.updateDraft(instructions, json(allowedTools), json(outputBlocks), json(positives), json(negatives), trim(command.defaultPrompt(), 1000), hash);
        versions.save(draft); definitions.save(definition);
        resources.deleteBySkillVersionId(draft.getId());
        saveResources(draft.getId(), resourceInputs);
        return detail(definition);
    }

    @Transactional
    public SkillDetail publish(String ownerId, String id) {
        SkillDefinition definition = ownedUser(ownerId, id);
        SkillVersion draft = definition.getDraftVersionId() == null ? null : versions.findById(definition.getDraftVersionId()).orElse(null);
        if (draft == null) throw conflict("SKILL_DRAFT_REQUIRED", "There is no Draft to publish");
        if (draft.getInstructions().isBlank() || list(draft.getOutputBlocks(), SkillBlockType.class).isEmpty()) {
            throw badRequest("SKILL_DRAFT_INVALID", "Instructions and at least one output block are required");
        }
        draft.publish(); versions.save(draft); definition.publish(draft.getId()); definitions.save(definition);
        return detail(definition);
    }

    @Transactional
    public TriggerPreview triggerPreview(String ownerId, String id) {
        SkillDefinition definition = ownedUser(ownerId, id);
        SkillVersion version = editableOrPublished(definition);
        List<String> positives = list(version.getShouldTrigger(), String.class);
        List<String> negatives = list(version.getShouldNotTrigger(), String.class);
        if (positives.isEmpty() && negatives.isEmpty()) throw badRequest("SKILL_TRIGGER_EXAMPLES_REQUIRED", "Add positive and negative trigger examples first");
        String previewHash = triggerHash(definition, version);
        try {
            List<Map<String, Object>> tests = new ArrayList<>();
            for (String value : positives) tests.add(Map.of("text", value, "expected", true));
            for (String value : negatives) tests.add(Map.of("text", value, "expected", false));
            AgentModelClient.AgentModelTurn turn = model.next(List.of(
                    AgentModelClient.AgentMessage.system("Evaluate whether each request should invoke the described Skill. Return JSON only: {\"results\":[{\"index\":integer,\"trigger\":boolean,\"reason\":string}]}. Evaluate every item exactly once."),
                    AgentModelClient.AgentMessage.user("Skill name: " + definition.getDisplayName() + "\nDescription: " + definition.getDescription() + "\nTests: " + json(tests))), List.of(), false);
            JsonNode result = mapper.readTree(stripCodeFence(turn.content()));
            List<TriggerConflict> conflicts = new ArrayList<>();
            Map<Integer, JsonNode> returned = new HashMap<>();
            result.path("results").forEach(value -> returned.put(value.path("index").asInt(-1), value));
            for (int index = 0; index < tests.size(); index++) {
                JsonNode value = returned.get(index); boolean actual = value != null && value.path("trigger").asBoolean(false);
                boolean expected = (boolean) tests.get(index).get("expected");
                if (value == null || actual != expected) conflicts.add(new TriggerConflict((String) tests.get(index).get("text"), expected, actual,
                        value == null ? "模型未返回该测试项" : value.path("reason").asText("触发判断与预期不符")));
            }
            boolean passed = conflicts.isEmpty(); version.markTriggerPreview(passed, previewHash); versions.save(version);
            return new TriggerPreview(passed, positives.size(), negatives.size(), conflicts);
        } catch (ProviderException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getCode(), "AI 路由预览当前不可用：" + exception.getMessage());
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILL_TRIGGER_PREVIEW_INVALID", "路由模型没有返回有效的触发测试结果"); }
    }

    @Transactional
    public SkillDetail autoEnable(String ownerId, String id) {
        SkillDefinition definition = ownedUser(ownerId, id);
        if (definition.getPublishedVersionId() == null) throw conflict("SKILL_NOT_PUBLISHED", "Publish the Skill before enabling automatic routing");
        SkillVersion version = versions.findById(definition.getPublishedVersionId()).orElseThrow();
        if (list(version.getShouldTrigger(), String.class).size() < 3 || list(version.getShouldNotTrigger(), String.class).size() < 3) {
            throw conflict("SKILL_TRIGGER_COVERAGE_REQUIRED", "Automatic routing requires at least 3 positive and 3 negative examples");
        }
        if (!version.isTriggerPreviewPassed() || !Objects.equals(version.getTriggerPreviewHash(), triggerHash(definition, version))) {
            throw conflict("SKILL_TRIGGER_PREVIEW_REQUIRED", "Run and pass the current trigger preview before enabling automatic routing");
        }
        definition.setInvocationPolicy(SkillInvocationPolicy.AUTO); definitions.save(definition);
        return detail(definition);
    }

    @Transactional
    public SkillDetail archive(String ownerId, String id) {
        SkillDefinition definition = ownedUser(ownerId, id); definition.archive(); definitions.save(definition); return detail(definition);
    }

    @Transactional
    public void delete(String ownerId, String id) {
        SkillDefinition definition = ownedUser(ownerId, id);
        List<SkillVersion> storedVersions = versions.findBySkillDefinitionIdOrderByVersionNumberDesc(definition.getId());
        List<String> versionIds = storedVersions.stream().map(SkillVersion::getId).toList();
        if (!versionIds.isEmpty() && runs.existsBySkillVersionIdInAndStatusIn(versionIds,
                List.of(KnowledgeRunStatus.PENDING, KnowledgeRunStatus.QUEUED, KnowledgeRunStatus.RUNNING))) {
            throw conflict("SKILL_HAS_ACTIVE_RUN", "This Skill is being used by an active Agent Run and cannot be deleted yet");
        }
        for (String versionId : versionIds) resources.deleteBySkillVersionId(versionId);
        versions.deleteAllInBatch(storedVersions);
        definitions.delete(definition);
    }

    @Transactional
    public SkillDetail duplicate(String ownerId, String id) {
        SkillDefinition source = visible(ownerId, id);
        SkillVersion sourceVersion = editableOrPublished(source);
        SkillDefinition copy = SkillDefinition.user(ownerId, source.getDisplayName() + " 副本", source.getDescription(), source.getSceneTypes(), source.getScopeTypes());
        definitions.save(copy);
        List<ResourceInput> copiedResources = resources.findBySkillVersionIdOrderBySortOrderAsc(sourceVersion.getId()).stream()
                .map(value -> new ResourceInput(value.getResourceKey(), value.getResourceType(), value.getName(), value.getPurpose(), value.getMarkdownContent())).toList();
        List<String> copiedTools = sanitizeTools(list(sourceVersion.getAllowedTools(), String.class));
        SkillVersion draft = versions.save(new SkillVersion(copy.getId(), 1, "v1", sourceVersion.getInstructions(), json(copiedTools),
                sourceVersion.getOutputBlocks(), sourceVersion.getShouldTrigger(), sourceVersion.getShouldNotTrigger(), sourceVersion.getDefaultPrompt(),
                contentHash(copy, sourceVersion.getInstructions(), copiedTools,
                        list(sourceVersion.getOutputBlocks(), SkillBlockType.class), list(sourceVersion.getShouldTrigger(), String.class),
                        list(sourceVersion.getShouldNotTrigger(), String.class), sourceVersion.getDefaultPrompt(), copiedResources)));
        saveResources(draft.getId(), copiedResources); copy.setDraftVersion(draft.getId()); definitions.save(copy); return detail(copy);
    }

    @Transactional
    public SkillDetail aiDraft(String ownerId, AiDraftCommand command) {
        String goal = required(command.goal(), 2000, "Skill goal");
        try {
            AgentModelClient.AgentModelTurn turn = model.next(List.of(
                    AgentModelClient.AgentMessage.system("Draft a private, read-only recording analysis Skill. Return JSON only with displayName, description, instructions, outputBlocks, shouldTrigger, shouldNotTrigger, defaultPrompt. outputBlocks must use SUMMARY,FINDINGS,DECISIONS,ACTION_ITEMS,OPEN_QUESTIONS,QA_REVIEW,ASSESSMENT_MATRIX,COMPARISON_TABLE. Include at least 3 positive and 3 negative trigger examples. Never include scripts, network access, hooks or write actions."),
                    AgentModelClient.AgentMessage.user("Goal: " + goal + "\nExample requests: " + json(normalizeExamples(command.examples())))), List.of(), false);
            JsonNode value = mapper.readTree(stripCodeFence(turn.content()));
            CreateCommand create = new CreateCommand(value.path("displayName").asText("我的 Skill"), value.path("description").asText(goal),
                    normalizeScenes(command.sceneTypes()), normalizeScopes(command.scopeTypes()));
            SkillDetail created = create(ownerId, create);
            List<SkillBlockType> blocks = new ArrayList<>();
            value.path("outputBlocks").forEach(item -> { try { blocks.add(SkillBlockType.valueOf(item.asText())); } catch (IllegalArgumentException ignored) { } });
            List<String> positives = strings(value.path("shouldTrigger")); List<String> negatives = strings(value.path("shouldNotTrigger"));
            return updateDraft(ownerId, created.id(), new DraftCommand(create.displayName(), create.description(), create.sceneTypes(), create.scopeTypes(),
                    value.path("instructions").asText("仅根据当前 Run 的听记证据完成目标；证据不足时明确说明。"), new ArrayList<>(USER_GRANTABLE_TOOLS),
                    blocks.isEmpty() ? DEFAULT_BLOCKS : blocks, positives, negatives, value.path("defaultPrompt").asText(null), List.of()));
        } catch (ProviderException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, exception.getCode(), "AI 草拟当前不可用，仍可使用手工创建：" + exception.getMessage());
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILL_AI_DRAFT_INVALID", "草拟模型没有返回有效的 Skill Draft"); }
    }

    private SkillVersion ensureDraft(SkillDefinition definition) {
        if (definition.getDraftVersionId() != null) return versions.findById(definition.getDraftVersionId()).orElseThrow();
        SkillVersion source = definition.getPublishedVersionId() == null ? null : versions.findById(definition.getPublishedVersionId()).orElseThrow();
        int next = versions.findBySkillDefinitionIdOrderByVersionNumberDesc(definition.getId()).stream().findFirst().map(value -> value.getVersionNumber() + 1).orElse(1);
        SkillVersion draft = source == null
                ? new SkillVersion(definition.getId(), next, "v" + next, "", json(USER_GRANTABLE_TOOLS), json(DEFAULT_BLOCKS), "[]", "[]", null, "pending")
                : new SkillVersion(definition.getId(), next, "v" + next, source.getInstructions(), source.getAllowedTools(), source.getOutputBlocks(),
                        source.getShouldTrigger(), source.getShouldNotTrigger(), source.getDefaultPrompt(), source.getContentHash());
        versions.save(draft);
        if (source != null) {
            List<ResourceInput> copied = resources.findBySkillVersionIdOrderBySortOrderAsc(source.getId()).stream()
                    .map(value -> new ResourceInput(value.getResourceKey(), value.getResourceType(), value.getName(), value.getPurpose(), value.getMarkdownContent())).toList();
            saveResources(draft.getId(), copied);
        }
        definition.setDraftVersion(draft.getId()); definitions.save(definition); return draft;
    }

    private SkillDefinition visible(String ownerId, String id) {
        return definitions.findById(id).filter(value -> value.getSource() == SkillSource.BUILTIN || Objects.equals(ownerId, value.getOwnerId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill was not found"));
    }
    private SkillDefinition ownedUser(String ownerId, String id) {
        SkillDefinition value = visible(ownerId, id);
        if (value.getSource() != SkillSource.USER || !Objects.equals(ownerId, value.getOwnerId())) throw new ApiException(HttpStatus.FORBIDDEN, "SKILL_READ_ONLY", "Built-in Skills are read-only; duplicate one to customize it");
        return value;
    }
    private SkillVersion editableOrPublished(SkillDefinition definition) {
        String id = definition.getDraftVersionId() != null ? definition.getDraftVersionId() : definition.getPublishedVersionId();
        if (id == null) throw conflict("SKILL_VERSION_REQUIRED", "Skill has no available version");
        return versions.findById(id).orElseThrow();
    }

    private SkillSummary summary(SkillDefinition value) {
        SkillVersion published = value.getPublishedVersionId() == null ? null : versions.findById(value.getPublishedVersionId()).orElse(null);
        SkillVersion draft = value.getDraftVersionId() == null ? null : versions.findById(value.getDraftVersionId()).orElse(null);
        SkillVersion shown = draft != null ? draft : published;
        return new SkillSummary(value.getId(), value.getDisplayName(), value.getDescription(), value.getSource(), value.getStatus(), value.getInvocationPolicy(),
                shown == null ? null : shown.getVersionName(), published == null ? null : published.getVersionName(), draft != null,
                enumList(value.getSceneTypes(), SceneType.class), enumList(value.getScopeTypes(), AgentScopeType.class),
                shown == null ? List.of() : enumList(shown.getOutputBlocks(), SkillBlockType.class), value.getUpdatedAt());
    }
    private SkillDetail detail(SkillDefinition value) {
        SkillVersion draft = value.getDraftVersionId() == null ? null : versions.findById(value.getDraftVersionId()).orElse(null);
        SkillVersion published = value.getPublishedVersionId() == null ? null : versions.findById(value.getPublishedVersionId()).orElse(null);
        return new SkillDetail(value.getId(), value.getDisplayName(), value.getDescription(), value.getSource(), value.getStatus(), value.getInvocationPolicy(),
                enumList(value.getSceneTypes(), SceneType.class), enumList(value.getScopeTypes(), AgentScopeType.class), versionView(draft, true),
                versionView(published, value.getSource() == SkillSource.USER), versions.findBySkillDefinitionIdOrderByVersionNumberDesc(value.getId()).stream().map(SkillVersion::getVersionName).toList(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
    private VersionView versionView(SkillVersion value, boolean includeContent) {
        if (value == null) return null;
        return new VersionView(value.getId(), value.getVersionName(), value.getInstructions(), list(value.getAllowedTools(), String.class),
                enumList(value.getOutputBlocks(), SkillBlockType.class), list(value.getShouldTrigger(), String.class), list(value.getShouldNotTrigger(), String.class),
                value.getDefaultPrompt(), value.getContentHash(), value.isTriggerPreviewPassed(), value.getPublishedAt(),
                resources.findBySkillVersionIdOrderBySortOrderAsc(value.getId()).stream().map(item -> new ResourceView(item.getId(), item.getResourceKey(),
                        item.getResourceType(), item.getName(), item.getPurpose(), includeContent ? item.getMarkdownContent() : null,
                        item.getMarkdownContent().getBytes(StandardCharsets.UTF_8).length)).toList());
    }

    private List<ResourceInput> validateResources(List<ResourceInput> input) {
        List<ResourceInput> values = input == null ? List.of() : input;
        if (values.size() > MAX_RESOURCES) throw badRequest("SKILL_RESOURCE_LIMIT", "A Skill can contain at most 10 resources");
        int total = 0; Set<String> keys = new HashSet<>(); List<ResourceInput> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            ResourceInput value = values.get(index); String content = Objects.toString(value.markdownContent(), "");
            int bytes = content.getBytes(StandardCharsets.UTF_8).length; total += bytes;
            if (bytes > MAX_RESOURCE_BYTES) throw badRequest("SKILL_RESOURCE_TOO_LARGE", "Each Skill resource is limited to 50 KB");
            String key = value.key() == null || value.key().isBlank() ? "resource-" + (index + 1) : value.key().trim();
            if (!key.matches("[A-Za-z0-9._/-]{1,160}") || !keys.add(key)) throw badRequest("SKILL_RESOURCE_KEY_INVALID", "Resource keys must be unique safe paths");
            result.add(new ResourceInput(key, Objects.requireNonNullElse(value.type(), SkillResourceType.REFERENCE), required(value.name(), 160, "Resource name"),
                    required(value.purpose(), 500, "Resource purpose"), content));
        }
        if (total > MAX_TOTAL_RESOURCE_BYTES) throw badRequest("SKILL_RESOURCES_TOO_LARGE", "Combined Skill resources are limited to 200 KB");
        return List.copyOf(result);
    }
    private void saveResources(String versionId, List<ResourceInput> values) {
        int index = 0;
        for (ResourceInput value : values) resources.save(new SkillResource(versionId, value.key(), value.type(), value.name(), value.purpose(),
                value.markdownContent(), Hashing.sha256(value.markdownContent()), index++));
    }
    private String contentHash(SkillDefinition definition, String instructions, List<String> tools, List<SkillBlockType> blocks,
                               List<String> positives, List<String> negatives, String defaultPrompt, List<ResourceInput> resourceInputs) {
        return Hashing.canonicalJsonHash(Map.ofEntries(Map.entry("name", definition.getDisplayName()), Map.entry("description", definition.getDescription()),
                Map.entry("scenes", definition.getSceneTypes()), Map.entry("scopes", definition.getScopeTypes()), Map.entry("instructions", instructions),
                Map.entry("tools", tools), Map.entry("blocks", blocks), Map.entry("positives", positives), Map.entry("negatives", negatives),
                Map.entry("defaultPrompt", Objects.toString(defaultPrompt, "")), Map.entry("resources", resourceInputs)));
    }
    private String triggerHash(SkillDefinition definition, SkillVersion version) {
        return Hashing.canonicalJsonHash(Map.of("name", definition.getDisplayName(), "description", definition.getDescription(),
                "positive", list(version.getShouldTrigger(), String.class), "negative", list(version.getShouldNotTrigger(), String.class)));
    }
    private List<String> sanitizeTools(List<String> requested) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : requested == null ? List.<String>of() : requested) if (USER_GRANTABLE_TOOLS.contains(value)) result.add(value);
        result.add("finalize_answer"); return List.copyOf(result);
    }
    private List<SkillBlockType> normalizeBlocks(List<SkillBlockType> values) {
        LinkedHashSet<SkillBlockType> result = new LinkedHashSet<>(Objects.requireNonNullElse(values, List.of()));
        if (result.isEmpty()) throw badRequest("SKILL_OUTPUT_REQUIRED", "Choose at least one output block");
        return List.copyOf(result);
    }
    private List<String> normalizeExamples(List<String> values) {
        return Objects.requireNonNullElse(values, List.<String>of()).stream().map(String::trim).filter(value -> !value.isBlank()).distinct().limit(20).toList();
    }
    private List<SceneType> normalizeScenes(List<SceneType> values) { return values == null || values.isEmpty() ? List.of(SceneType.values()) : List.copyOf(new LinkedHashSet<>(values)); }
    private List<AgentScopeType> normalizeScopes(List<AgentScopeType> values) { return values == null || values.isEmpty() ? List.of(AgentScopeType.values()) : List.copyOf(new LinkedHashSet<>(values)); }
    private String required(String value, int max, String label) { String result = value == null ? "" : value.trim(); if (result.isBlank() || result.length() > max) throw badRequest("SKILL_FIELD_INVALID", label + " must contain 1 to " + max + " characters"); return result; }
    private String trim(String value, int max) { if (value == null || value.isBlank()) return null; String result = value.trim(); return result.substring(0, Math.min(max, result.length())); }
    private List<String> strings(JsonNode values) { List<String> result = new ArrayList<>(); values.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().trim()); }); return result; }
    private String stripCodeFence(String value) { String trimmed = Objects.toString(value, "").trim(); if (!trimmed.startsWith("```")) return trimmed; int first = trimmed.indexOf('\n'); int last = trimmed.lastIndexOf("```"); return first >= 0 && last > first ? trimmed.substring(first + 1, last).trim() : trimmed; }
    private String json(Object value) { try { return mapper.writeValueAsString(Objects.requireNonNullElse(value, List.of())); } catch (Exception exception) { throw new IllegalStateException("Cannot serialize Skill data", exception); } }
    private <T> List<T> list(String value, Class<T> type) { try { return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, type)); } catch (Exception exception) { throw new IllegalStateException("Stored Skill data is invalid", exception); } }
    private <T extends Enum<T>> List<T> enumList(String value, Class<T> type) { return list(value, type); }
    private ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    public record CreateCommand(String displayName, String description, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes) { }
    public record AiDraftCommand(String goal, List<String> examples, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes) { }
    public record DraftCommand(String displayName, String description, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes,
                               String instructions, List<String> allowedTools, List<SkillBlockType> outputBlocks,
                               List<String> shouldTrigger, List<String> shouldNotTrigger, String defaultPrompt, List<ResourceInput> resources) { }
    public record ResourceInput(String key, SkillResourceType type, String name, String purpose, String markdownContent) { }
    public record SkillSummary(String id, String displayName, String description, SkillSource source, SkillStatus status,
                               SkillInvocationPolicy invocationPolicy, String version, String publishedVersion, boolean hasDraft,
                               List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes, List<SkillBlockType> outputBlocks,
                               java.time.Instant updatedAt) { }
    public record SkillDetail(String id, String displayName, String description, SkillSource source, SkillStatus status,
                              SkillInvocationPolicy invocationPolicy, List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes,
                              VersionView draft, VersionView published, List<String> versions, java.time.Instant createdAt, java.time.Instant updatedAt) { }
    public record VersionView(String id, String version, String instructions, List<String> allowedTools, List<SkillBlockType> outputBlocks,
                              List<String> shouldTrigger, List<String> shouldNotTrigger, String defaultPrompt, String contentHash,
                              boolean triggerPreviewPassed, java.time.Instant publishedAt, List<ResourceView> resources) { }
    public record ResourceView(String id, String key, SkillResourceType type, String name, String purpose, String markdownContent, int sizeBytes) { }
    public record TriggerConflict(String text, boolean expected, boolean actual, String reason) { }
    public record TriggerPreview(boolean passed, int positiveCount, int negativeCount, List<TriggerConflict> conflicts) { }
}
