package com.voicenote.web;

import com.voicenote.service.ProfileService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    ProfileService.ProfileView get(Authentication authentication) {
        return profiles.get(CurrentUser.require(authentication).id());
    }
}
