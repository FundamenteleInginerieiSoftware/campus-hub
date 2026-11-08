package com.campus.incident.domain;

import com.campus.common.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "incident_reports")
@Getter
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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_id")
    private Long resolvedById;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Version
    private Long version = 0L;

    public IncidentReport(
            Long assetId,
            Long reservationId,
            Long reportedById,
            String description,
            IncidentSeverity severity,
            String photoKey,
            LocalDateTime createdAt) {
        this.assetId = assetId;
        this.reservationId = reservationId;
        this.reportedById = reportedById;
        this.description = description;
        this.severity = severity;
        this.status = IncidentStatus.OPEN;
        this.photoKey = photoKey;
        this.createdAt = createdAt;
    }

    /** OPEN to IN_REPAIR: an admin has picked the item up for repair. The asset stays UNDER_MAINTENANCE. */
    public void startRepair() {
        if (this.status != IncidentStatus.OPEN) {
            throw new InvalidStateTransitionException(
                    "INVALID_INCIDENT_STATUS_TRANSITION", "Repair cannot start from status: " + this.status);
        }
        this.status = IncidentStatus.IN_REPAIR;
    }

    /**
     * OPEN or IN_REPAIR to REJECTED: the report was wrong (the item works). Like resolve, it fills the
     * resolved* fields, so they always tell who closed the incident, when and why.
     */
    public void reject(Long adminId, String resolutionNotes, LocalDateTime rejectedAt) {
        if (this.status != IncidentStatus.OPEN && this.status != IncidentStatus.IN_REPAIR) {
            throw new InvalidStateTransitionException(
                    "INVALID_INCIDENT_STATUS_TRANSITION", "Incident cannot be rejected from status: " + this.status);
        }
        this.status = IncidentStatus.REJECTED;
        this.resolvedById = adminId;
        this.resolutionNotes = resolutionNotes;
        this.resolvedAt = rejectedAt;
    }

    /**
     * A photo can be added or replaced only while the incident is still OPEN or IN_REPAIR. Called before the
     * file is stored, so a closed incident never leaves an unused file behind.
     */
    public void checkPhotoCanChange() {
        if (this.status != IncidentStatus.OPEN && this.status != IncidentStatus.IN_REPAIR) {
            throw new InvalidStateTransitionException(
                    "INVALID_INCIDENT_STATUS_TRANSITION", "Photo cannot change when the incident is " + this.status);
        }
    }

    /** Stores the key returned by StorageService (never a path or a URL). */
    public void attachPhoto(String photoKey) {
        checkPhotoCanChange();
        this.photoKey = photoKey;
    }

    public void resolve(Long adminId, String resolutionNotes, LocalDateTime resolvedAt) {
        if (this.status != IncidentStatus.OPEN && this.status != IncidentStatus.IN_REPAIR) {
            throw new InvalidStateTransitionException(
                    "INVALID_INCIDENT_STATUS_TRANSITION", "Incident cannot be resolved from status: " + this.status);
        }
        this.status = IncidentStatus.RESOLVED;
        this.resolvedById = adminId;
        this.resolutionNotes = resolutionNotes;
        this.resolvedAt = resolvedAt;
    }

    public enum IncidentSeverity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum IncidentStatus {
        OPEN,
        IN_REPAIR,
        RESOLVED,
        REJECTED
    }
}
