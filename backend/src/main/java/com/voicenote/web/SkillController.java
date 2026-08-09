package com.voicenote.web;

import com.voicenote.domain.*;
import com.voicenote.service.SkillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillService skills;
    public SkillController(SkillService skills) { this.skills = skills; }

    @GetMapping
    List<SkillService.SkillSummary> list(Authentication authentication) {
        return skills.list(CurrentUser.require(authentication).id());
    }

    @GetMapping("/{id}")
    SkillService.SkillDetail get(@PathVariable String id, Authentication authentication) {
        return skills.get(CurrentUser.require(authentication).id(), id);
    }

    @PostMapping
    SkillService.SkillDetail create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return skills.create(CurrentUser.require(authentication).id(), new SkillService.CreateCommand(
                request.displayName(), request.description(), request.sceneTypes(), request.scopeTypes()));
    }

    @PostMapping("/ai-draft")
    SkillService.SkillDetail aiDraft(@Valid @RequestBody AiDraftRequest request, Authentication authentication) {
        return skills.aiDraft(CurrentUser.require(authentication).id(), new SkillService.AiDraftCommand(
                request.goal(), request.examples(), request.sceneTypes(), request.scopeTypes()));
    }

    @PutMapping("/{id}/draft")
    SkillService.SkillDetail updateDraft(@PathVariable String id, @Valid @RequestBody DraftRequest request, Authentication authentication) {
        return skills.updateDraft(CurrentUser.require(authentication).id(), id, new SkillService.DraftCommand(
                request.displayName(), request.description(), request.sceneTypes(), request.scopeTypes(), request.instructions(),
                request.allowedTools(), request.outputBlocks(), request.shouldTrigger(), request.shouldNotTrigger(), request.defaultPrompt(),
                request.resources() == null ? List.of() : request.resources().stream().map(ResourceRequest::toInput).toList()));
    }

    @PostMapping("/{id}/trigger-preview")
    SkillService.TriggerPreview triggerPreview(@PathVariable String id, Authentication authentication) {
        return skills.triggerPreview(CurrentUser.require(authentication).id(), id);
    }

    @PostMapping("/{id}/publish")
    SkillService.SkillDetail publish(@PathVariable String id, Authentication authentication) {
        return skills.publish(CurrentUser.require(authentication).id(), id);
    }

    @PostMapping("/{id}/auto-enable")
    SkillService.SkillDetail autoEnable(@PathVariable String id, Authentication authentication) {
        return skills.autoEnable(CurrentUser.require(authentication).id(), id);
    }

    @PostMapping("/{id}/archive")
    SkillService.SkillDetail archive(@PathVariable String id, Authentication authentication) {
        return skills.archive(CurrentUser.require(authentication).id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id, Authentication authentication) {
        skills.delete(CurrentUser.require(authentication).id(), id);
    }

    @PostMapping("/{id}/duplicate")
    SkillService.SkillDetail duplicate(@PathVariable String id, Authentication authentication) {
        return skills.duplicate(CurrentUser.require(authentication).id(), id);
    }

    public record CreateRequest(@NotBlank @Size(max = 120) String displayName, @NotBlank @Size(max = 1000) String description,
                                List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes) { }
    public record AiDraftRequest(@NotBlank @Size(max = 2000) String goal, @Size(max = 20) List<@Size(max = 1000) String> examples,
                                 List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes) { }
    public record DraftRequest(@NotBlank @Size(max = 120) String displayName, @NotBlank @Size(max = 1000) String description,
                               List<SceneType> sceneTypes, List<AgentScopeType> scopeTypes,
                               @NotBlank @Size(max = SkillService.MAX_INSTRUCTIONS) String instructions,
                               List<String> allowedTools, @NotEmpty List<SkillBlockType> outputBlocks,
                               @Size(max = 20) List<@Size(max = 1000) String> shouldTrigger,
                               @Size(max = 20) List<@Size(max = 1000) String> shouldNotTrigger,
                               @Size(max = 1000) String defaultPrompt, @Size(max = SkillService.MAX_RESOURCES) List<@Valid ResourceRequest> resources) { }
    public record ResourceRequest(@Size(max = 160) String key, @NotNull SkillResourceType type,
                                  @NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 500) String purpose,
                                  @NotNull String markdownContent) {
        SkillService.ResourceInput toInput() { return new SkillService.ResourceInput(key, type, name, purpose, markdownContent); }
    }
}
