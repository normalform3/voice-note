package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private String id;
    @Column(nullable = false, unique = true) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UserAccount() { }
    public UserAccount(String email, String passwordHash) {
        this.id = UUID.randomUUID().toString(); this.email = email; this.passwordHash = passwordHash; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
}
