package com.campus.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stores the SHA-256 hash of a user's private iCalendar subscription token.
 *
 * <p>Each user has at most one row ({@code user_id} is unique). Generating a new link replaces the
 * hash in place via {@link #replaceToken(String, LocalDateTime)} so the old link stops working
 * immediately.
 */
@Entity
@Table(name = "calendar_feeds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalendarFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CalendarFeed(Long userId, String tokenHash, LocalDateTime createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.createdAt =
                Objects.requireNonNull(createdAt, "createdAt must not be null").truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Replaces the stored token hash when the user rotates their calendar link, invalidating any
     * previously issued URL.
     */
    public void replaceToken(String tokenHash, LocalDateTime now) {
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.createdAt = Objects.requireNonNull(now, "now must not be null").truncatedTo(ChronoUnit.SECONDS);
    }
}
