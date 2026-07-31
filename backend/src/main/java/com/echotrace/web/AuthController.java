package com.echotrace.web;

import com.echotrace.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") public AuthService.AuthResult register(@Valid @RequestBody Credentials request) { return auth.register(request.email(), request.password()); }
    @PostMapping("/login") public AuthService.AuthResult login(@Valid @RequestBody Credentials request) { return auth.login(request.email(), request.password()); }
    public record Credentials(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 128) String password) { }
}
