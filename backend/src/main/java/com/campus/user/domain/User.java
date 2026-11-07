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
import java.time.temporal.ChronoUnit;
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
        // Bug fixed (#134): LocalDateTime.now(clock) has nanoseconds, but PostgreSQL TIMESTAMP keeps only
        // microseconds, so the saved user and the same row read back later had different times.
        // Fix: keep whole seconds, the same format as every time in the API (#123 did this for reservations).
        this.createdAt = createdAt.truncatedTo(ChronoUnit.SECONDS);
    }

    // ---- user admin (Stefania, user.admin) ----------------------------------------------------
    // Only AdminUserService calls these, after its checks (not yourself, never the last admin).

    /** Blocks the account: SecurityConfig rejects its token on the next request (401), login fails. */
    public void disable() {
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }

    /** The new role is used from the next request: SecurityConfig reads the role from this row, not the token. */
    public void changeRole(Role newRole) {
        this.role = newRole;
    }
}
