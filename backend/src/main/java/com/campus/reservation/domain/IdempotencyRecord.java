package com.campus.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    // When the current attempt started. restart() moves it forward, so a stuck IN_PROGRESS
    // row can be recognised by its age (see IdempotencyService.ABANDONED_AFTER).
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public IdempotencyRecord(
            String idempotencyKey, Long userId, String requestFingerprint, LocalDateTime now, LocalDateTime expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    // Reuses the same row for a new attempt (after FAILED, an abandoned IN_PROGRESS or expiry).
    // Updating instead of delete + insert avoids the unique-key clash: Hibernate sends the INSERT
    // at once (IDENTITY id) but the DELETE only at flush, so the INSERT hit uq_idempotency_key_user.
    public void restart(String requestFingerprint, LocalDateTime now, LocalDateTime expiresAt) {
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.responseStatus = null;
        this.responseBody = null;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public void markCompleted(int responseStatus, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public void markFailed() {
        this.status = IdempotencyStatus.FAILED;
    }

    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(expiresAt);
    }
}
