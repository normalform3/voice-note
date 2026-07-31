package com.echotrace.service;

import com.echotrace.domain.UserAccount;
import com.echotrace.repository.UserAccountRepository;
import com.echotrace.security.JwtService;
import com.echotrace.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    public AuthService(UserAccountRepository users, PasswordEncoder encoder, JwtService jwt) { this.users = users; this.encoder = encoder; this.jwt = jwt; }

    @Transactional
    public AuthResult register(String email, String password) {
        String normalized = email.trim().toLowerCase();
        if (users.findByEmailIgnoreCase(normalized).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        UserAccount account = users.save(new UserAccount(normalized, encoder.encode(password)));
        return new AuthResult(account.getId(), account.getEmail(), jwt.issue(account.getId(), account.getEmail()));
    }

    public AuthResult login(String email, String password) {
        UserAccount account = users.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect"));
        if (!encoder.matches(password, account.getPasswordHash())) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect");
        return new AuthResult(account.getId(), account.getEmail(), jwt.issue(account.getId(), account.getEmail()));
    }
    public record AuthResult(String userId, String email, String accessToken) { }
}
