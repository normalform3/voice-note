package com.voicenote.web;

import com.voicenote.domain.AgentConversationStatus;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.service.AgentConversationService;
import com.voicenote.service.KnowledgeAgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/agent-conversations")
public class AgentConversationController {
    private final AgentConversationService conversations;
    public AgentConversationController(AgentConversationService conversations) { this.conversations = conversations; }

    @PostMapping AgentConversationService.ConversationView create(@Valid @RequestBody CreateConversationRequest request, Authentication authentication) {
        return conversations.create(CurrentUser.require(authentication).id(), new AgentConversationService.CreateConversationCommand(
                request.title(), request.scope().type(), request.scope().transcriptionTaskIds(), request.skillId(), request.timeZone(),
                request.memoryEnabled() == null || request.memoryEnabled()));
    }
    @GetMapping Page<AgentConversationService.ConversationView> list(@RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size,
                                                                     Authentication authentication) {
        return conversations.list(CurrentUser.require(authentication).id(), page, size);
    }
    @GetMapping("/{conversationId}") AgentConversationService.ConversationDetail get(@PathVariable String conversationId, Authentication authentication) {
        return conversations.detail(CurrentUser.require(authentication).id(), conversationId);
    }
    @PostMapping("/{conversationId}/turns") KnowledgeAgentService.AgentRunView turn(@PathVariable String conversationId,
                                                                                     @RequestHeader("Idempotency-Key") String key,
                                                                                     @Valid @RequestBody CreateTurnRequest request,
                                                                                     Authentication authentication) {
        return conversations.createTurn(CurrentUser.require(authentication).id(), key, conversationId, request.message());
    }
    @PatchMapping("/{conversationId}") AgentConversationService.ConversationView update(@PathVariable String conversationId,
                                                                                          @Valid @RequestBody UpdateConversationRequest request,
                                                                                          Authentication authentication) {
        return conversations.update(CurrentUser.require(authentication).id(), conversationId,
                new AgentConversationService.UpdateConversationCommand(request.title(), request.status(), request.memoryEnabled()));
    }
    @DeleteMapping("/{conversationId}") void delete(@PathVariable String conversationId, Authentication authentication) {
        conversations.delete(CurrentUser.require(authentication).id(), conversationId);
    }

    public record ScopeRequest(@NotNull AgentScopeType type, @Size(max = 50) List<String> transcriptionTaskIds) { }
    public record CreateConversationRequest(@Size(max = 160) String title, @NotNull @Valid ScopeRequest scope,
                                            String skillId, @NotBlank String timeZone, Boolean memoryEnabled) { }
    public record CreateTurnRequest(@NotBlank @Size(max = 8000) String message) { }
    public record UpdateConversationRequest(@Size(max = 160) String title, AgentConversationStatus status, Boolean memoryEnabled) { }
}
