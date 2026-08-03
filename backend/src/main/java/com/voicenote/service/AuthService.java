package com.voicenote.service;

import com.voicenote.domain.PasswordScheme;
import com.voicenote.domain.UserAccount;
import com.voicenote.repository.UserAccountRepository;
import com.voicenote.security.JwtService;
import com.voicenote.web.ApiException;
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
    public AuthResult register(String account, String password) {
        AccountPolicy.requireValidAccount(account);
        if (users.findByAccount(account).isPresent()) throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "Account is already registered");
        UserAccount user = users.save(new UserAccount(account, encoder.encode(AccountPolicy.passwordDigest(password))));
        return new AuthResult(user.getId(), user.getAccount(), jwt.issue(user.getId(), user.getAccount()));
    }

    public AuthResult login(String account, String password) {
        AccountPolicy.requireValidAccount(account);
        UserAccount user = users.findByAccount(account)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Account or password is incorrect"));
        String candidate = user.getPasswordScheme() == PasswordScheme.LEGACY_BCRYPT ? password : AccountPolicy.passwordDigest(password);
        if (!encoder.matches(candidate, user.getPasswordHash())) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Account or password is incorrect");
        return new AuthResult(user.getId(), user.getAccount(), jwt.issue(user.getId(), user.getAccount()));
    }

    public record AuthResult(String userId, String account, String accessToken) { }
}
