package com.voicenote.web;

import com.voicenote.service.HotwordLibraryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hotword-libraries")
public class HotwordLibraryController {
    private final HotwordLibraryService libraries;
    public HotwordLibraryController(HotwordLibraryService libraries) { this.libraries = libraries; }

    @GetMapping HotwordLibraryService.Catalog list(Authentication authentication) {
        return libraries.list(CurrentUser.require(authentication).id());
    }
    @PostMapping ResponseEntity<HotwordLibraryService.LibraryView> create(@RequestHeader("Idempotency-Key") String key,
                                                                           @RequestBody HotwordLibraryService.SaveCommand request,
                                                                           Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(libraries.create(CurrentUser.require(authentication).id(), key, request));
    }
    @PutMapping("/{id}") HotwordLibraryService.LibraryView update(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                                    @RequestBody HotwordLibraryService.SaveCommand request,
                                                                    Authentication authentication) {
        return libraries.update(CurrentUser.require(authentication).id(), key, id, request);
    }
    @DeleteMapping("/{id}") HotwordLibraryService.LibraryView delete(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                                       Authentication authentication) {
        return libraries.delete(CurrentUser.require(authentication).id(), key, id);
    }
    @PostMapping("/{id}/retry-sync") HotwordLibraryService.LibraryView retry(@PathVariable String id, @RequestHeader("Idempotency-Key") String key,
                                                                              Authentication authentication) {
        return libraries.retrySync(CurrentUser.require(authentication).id(), key, id);
    }
}
