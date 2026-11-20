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
 * Audit log recording the outcome of a reservation reassignment attempt triggered by an incident report
 * or administrative asset retirement (#245).
 */
@Entity
@Table(name = "reassignment_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReassignmentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable for administrative asset retirements (#245), which have no associated incident report.
    @Column(name = "incident_id")
    private Long incidentId;

    // Column is named triggered_by to avoid quoting the SQL keyword TRIGGER in external tools (#311 follow-up).
    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 32)
    private ReassignmentTrigger trigger;

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
            ReassignmentTrigger trigger,
            Long reservationId,
            Long fromAssetId,
            Long toAssetId,
            ReassignmentOutcome outcome,
            String strategy,
            LocalDateTime createdAt) {
        this.incidentId = incidentId;
        this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.fromAssetId = Objects.requireNonNull(fromAssetId, "fromAssetId must not be null");
        this.toAssetId = toAssetId;
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.strategy = strategy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
