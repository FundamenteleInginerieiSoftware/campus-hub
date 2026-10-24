package com.campus.user.domain;

import com.campus.user.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A row of the {@code users} table. Never leaves the user module: other modules get a {@code
 * UserSummary} through {@code UserApi}, and controllers return response records.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Hibernate needs an empty constructor; we don't
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // PostgreSQL picks the id on insert
    private Long id;

    private String email;

    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean enabled;

    // updatable = false: once written, Hibernate never changes it again.
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * A new, enabled user. {@code createdAt} comes from the caller ({@code LocalDateTime.now(clock)})
     * so tests can use a fixed clock.
     */
    public User(String email, String passwordHash, String fullName, Role role, LocalDateTime createdAt) {
        // Lowercase once here, so the UNIQUE column and every lookup agree on one spelling.
        this.email = email.trim().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.fullName = fullName.trim();
        this.role = role;
        this.enabled = true;
        this.createdAt = createdAt;
    }
}
