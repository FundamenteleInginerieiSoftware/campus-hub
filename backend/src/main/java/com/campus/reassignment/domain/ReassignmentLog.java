package com.campus.reassignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Audit log recording the outcome of a reservation reassignment attempt triggered by an incident report.
 */
@Entity
@Table(name = "reassignment_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReassignmentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "from_asset_id", nullable = false)
    private Long fromAssetId;

    @Column(name = "to_asset_id")
    private Long toAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ReassignmentOutcome outcome;

    @Column(name = "strategy", length = 64)
    private String strategy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ReassignmentLog(
            Long incidentId,
            Long reservationId,
            Long fromAssetId,
            Long toAssetId,
            ReassignmentOutcome outcome,
            String strategy,
            LocalDateTime createdAt) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.fromAssetId = Objects.requireNonNull(fromAssetId, "fromAssetId must not be null");
        this.toAssetId = toAssetId;
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.strategy = strategy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
