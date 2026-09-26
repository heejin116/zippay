package com.zippay.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name="users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {}

    public static User create(String email, String passwordHash) {
        User user = new User();
        user.email = email.toLowerCase(Locale.ROOT);   // 소문자 정규화
        user.passwordHash = passwordHash;
        user.role = Role.USER;                         // 가입 시 기본 역할
        return user;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}