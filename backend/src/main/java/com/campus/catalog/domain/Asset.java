package com.campus.catalog.domain;

import com.campus.catalog.api.AssetStatus;
import com.campus.common.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    // Required for Calin's SameModelStrategy during automatic reassignment.
    @Column(nullable = false, length = 150)
    private String model;

    @Column(name = "serial_number", nullable = false, unique = true, length = 100)
    private String serialNumber;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssetStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lab_room_id", nullable = false)
    private LabRoom labRoom;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Asset(
            String name,
            String model,
            String serialNumber,
            String description,
            Category category,
            LabRoom labRoom,
            LocalDateTime now) {
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.description = description;
        this.category = category;
        this.labRoom = labRoom;
        this.status = AssetStatus.AVAILABLE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markUnderMaintenance(LocalDateTime now) {
        if (this.status == AssetStatus.RETIRED) {
            throw new InvalidStateTransitionException(
                    "ASSET_RETIRED", "Cannot mark a retired asset as under maintenance");
        }
        this.status = AssetStatus.UNDER_MAINTENANCE;
        this.updatedAt = now;
    }

    public void markAvailable(LocalDateTime now) {
        if (this.status == AssetStatus.RETIRED) {
            throw new InvalidStateTransitionException("ASSET_RETIRED", "Cannot mark a retired asset as available");
        }
        this.status = AssetStatus.AVAILABLE;
        this.updatedAt = now;
    }

    public void retire(LocalDateTime now) {
        this.status = AssetStatus.RETIRED;
        this.updatedAt = now;
    }

    public void updateDetails(
            String name, String model, String description, Category category, LabRoom labRoom, LocalDateTime now) {
        if (this.status == AssetStatus.RETIRED) {
            throw new InvalidStateTransitionException("ASSET_RETIRED", "Cannot update a retired asset");
        }
        this.name = name;
        this.model = model;
        this.description = description;
        this.category = category;
        this.labRoom = labRoom;
        this.updatedAt = now;
    }
}
