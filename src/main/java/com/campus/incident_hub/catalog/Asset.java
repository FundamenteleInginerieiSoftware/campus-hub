package com.campus.incident_hub.catalog;

import com.campus.incident_hub.common.exception.BusinessRuleException;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "assets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @NotNull(message = "Numele echipamentului nu poate fi gol.")
    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 150, nullable = false)
    private String model;

    @NotNull(message = "Numărul de serie este obligatoriu.")
    @Column(nullable = false, unique = true, length = 100, name = "serial_number")
    private String serialNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @NotNull(message = "Statusul este obligatoriu.")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
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

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;


    public Asset(String name, String model, String serialNumber, String description, Category category, LabRoom labRoom){
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.description = description;
        this.category = category;
        this.labRoom = labRoom;
        this.status = AssetStatus.AVAILABLE;
    }

    public void markUnderMaintenance() {
        if (this.status == AssetStatus.RETIRED) {
            throw new BusinessRuleException("ASSET_RETIRED", "Asset is retired");
        }
        this.status = AssetStatus.UNDER_MAINTENANCE;
    }

    public void markAvailable() {
        if (this.status == AssetStatus.RETIRED) {
            throw new BusinessRuleException("ASSET_RETIRED", "Asset is retired");
        }
        this.status = AssetStatus.AVAILABLE;
    }

    public void retire() {
        this.status = AssetStatus.RETIRED;
    }

    public void updateDetails(String name, String model, String description, Category category, LabRoom labRoom) {
        this.name = name;
        this.model = model;
        this.description = description;
        this.category = category;
        this.labRoom = labRoom;
    }
}
