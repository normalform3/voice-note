package com.voicenote.web;

import com.voicenote.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") public AuthService.AuthResult register(@Valid @RequestBody Credentials request) { return auth.register(request.account(), request.password()); }
    @PostMapping("/login") public AuthService.AuthResult login(@Valid @RequestBody Credentials request) { return auth.login(request.account(), request.password()); }
    public record Credentials(@NotBlank @Pattern(regexp = "\\S+", message = "Account cannot contain whitespace") String account, @NotEmpty String password) { }
}
