package com.campus.catalog.web;

import com.campus.catalog.domain.LabRoom;

public record LabRoomResponse(Long id, String code, String name, String building) {

    public static LabRoomResponse from(LabRoom labRoom) {
        return new LabRoomResponse(labRoom.getId(), labRoom.getCode(), labRoom.getName(), labRoom.getBuilding());
    }
}
