package com.voicenote.web;

import com.voicenote.domain.UserMemoryCandidateStatus;
import com.voicenote.service.UserMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/user-memory-candidates")
public class UserMemoryCandidateController {
    private final UserMemoryService memories;
    public UserMemoryCandidateController(UserMemoryService memories) { this.memories = memories; }
    @GetMapping List<UserMemoryService.CandidateView> list(@RequestParam(defaultValue = "PENDING") UserMemoryCandidateStatus status,
                                                           Authentication authentication) {
        return memories.candidateViews(CurrentUser.require(authentication).id(), status);
    }
    @PostMapping("/{candidateId}/confirm") UserMemoryService.MemoryView confirm(@PathVariable String candidateId,
                                                                                @Valid @RequestBody(required = false) ConfirmRequest request,
                                                                                Authentication authentication) {
        return memories.confirm(CurrentUser.require(authentication).id(), candidateId, request == null ? null : request.content());
    }
    @PostMapping("/{candidateId}/reject") void reject(@PathVariable String candidateId, Authentication authentication) {
        memories.reject(CurrentUser.require(authentication).id(), candidateId);
    }
    @PostMapping("/{candidateId}/retry") UserMemoryService.CandidateView retry(@PathVariable String candidateId, Authentication authentication) {
        return memories.retry(CurrentUser.require(authentication).id(), candidateId);
    }
    public record ConfirmRequest(@Size(max = 2000) String content) { }
}
