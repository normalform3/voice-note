package com.voicenote.web;

import com.voicenote.domain.UserMemoryCategory;
import com.voicenote.service.UserMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/user-memories")
public class UserMemoryController {
    private final UserMemoryService memories;
    public UserMemoryController(UserMemoryService memories) { this.memories = memories; }
    @GetMapping List<UserMemoryService.MemoryView> list(Authentication authentication) {
        return memories.memoryViews(CurrentUser.require(authentication).id());
    }
    @PatchMapping("/{memoryId}") UserMemoryService.MemoryView update(@PathVariable String memoryId,
                                                                     @Valid @RequestBody UpdateMemoryRequest request,
                                                                     Authentication authentication) {
        return memories.update(CurrentUser.require(authentication).id(), memoryId, request.content(), request.category());
    }
    @DeleteMapping("/{memoryId}") void delete(@PathVariable String memoryId, Authentication authentication) {
        memories.delete(CurrentUser.require(authentication).id(), memoryId);
    }
    public record UpdateMemoryRequest(@NotBlank @Size(max = 2000) String content, UserMemoryCategory category) { }
}
