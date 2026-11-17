package com.campus.catalog.web;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.domain.Asset;
import java.time.LocalDateTime;

public record AssetResponse(
        Long id,
        String name,
        String model,
        String serialNumber,
        String description,
        AssetStatus status,
        Long categoryId,
        String categoryName,
        Long labRoomId,
        String labRoomCode,
        String labRoomName,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public AssetResponse(
            Long id,
            String name,
            String model,
            String serialNumber,
            String description,
            AssetStatus status,
            Long categoryId,
            String categoryName,
            Long labRoomId,
            String labRoomCode,
            String labRoomName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(
                id,
                name,
                model,
                serialNumber,
                description,
                status,
                categoryId,
                categoryName,
                labRoomId,
                labRoomCode,
                labRoomName,
                0L,
                createdAt,
                updatedAt);
    }

    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getName(),
                asset.getModel(),
                asset.getSerialNumber(),
                asset.getDescription(),
                asset.getStatus(),
                asset.getCategory().getId(),
                asset.getCategory().getName(),
                asset.getLabRoom().getId(),
                asset.getLabRoom().getCode(),
                asset.getLabRoom().getName(),
                asset.getVersion(),
                asset.getCreatedAt(),
                asset.getUpdatedAt());
    }
}
