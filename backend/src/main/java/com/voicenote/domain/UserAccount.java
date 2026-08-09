package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(nullable = false, unique = true, length = 320) private String account;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(name = "password_scheme", nullable = false) private PasswordScheme passwordScheme;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UserAccount() { }
    public UserAccount(String account, String passwordHash) {
        this.id = UUID.randomUUID().toString(); this.account = account; this.passwordHash = passwordHash;
        this.passwordScheme = PasswordScheme.SHA256_BCRYPT; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getAccount() { return account; }
    public String getPasswordHash() { return passwordHash; }
    public PasswordScheme getPasswordScheme() { return passwordScheme; }
    public Instant getCreatedAt() { return createdAt; }
}
