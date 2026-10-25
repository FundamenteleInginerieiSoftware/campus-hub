package com.campus.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "incident_reports")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "reported_by_id", nullable = false)
    private Long reportedById;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IncidentStatus status;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_id")
    private Long resolvedById;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Version
    private Long version;

    public IncidentReport(
            Long assetId,
            Long reservationId,
            Long reportedById,
            String description,
            IncidentSeverity severity,
            String photoKey,
            Instant createdAt) {
        this.assetId = assetId;
        this.reservationId = reservationId;
        this.reportedById = reportedById;
        this.description = description;
        this.severity = severity;
        this.photoKey = photoKey;
        this.status = IncidentStatus.OPEN;
        this.createdAt = createdAt;
    }

    public void resolve(Long adminId, String notes, Instant resolvedAt) {
        if (this.status != IncidentStatus.OPEN && this.status != IncidentStatus.IN_REPAIR) {
            throw new IllegalStateException("Incident cannot be resolved from status: " + this.status);
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedById = adminId;
        this.resolutionNotes = notes;
        this.resolvedAt = resolvedAt;
    }

    public enum IncidentSeverity {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum IncidentStatus {
        OPEN,
        IN_REPAIR,
        RESOLVED,
        REJECTED
    }
}
