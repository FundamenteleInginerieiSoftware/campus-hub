package com.campus.incident.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
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

    @Column(name = "reported_by_id", nullable = false)
    private Long reportedById;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(name = "photo_key")
    private String photoKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by_id")
    private Long resolvedById;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Version
    private Long version;

    public enum IncidentStatus {
        OPEN,
        IN_REPAIR,
        RESOLVED,
        REJECTED
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
