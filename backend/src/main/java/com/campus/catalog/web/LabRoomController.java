package com.campus.catalog.web;

import com.campus.catalog.service.LabRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Lab Rooms", description = "Laboratory rooms lookup and admin CRUD")
public class LabRoomController {

    private final LabRoomService labRoomService;

    @GetMapping("/api/v1/lab-rooms")
    @Operation(summary = "List all laboratory rooms sorted by code")
    public List<LabRoomResponse> listAll() {
        return labRoomService.listAll();
    }

    @PostMapping("/api/v1/admin/lab-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a lab room (409 LAB_ROOM_CODE_TAKEN if duplicate)")
    public LabRoomResponse create(@Valid @RequestBody LabRoomRequest request) {
        return labRoomService.create(request, AdminActor.currentUserId());
    }

    @PutMapping("/api/v1/admin/lab-rooms/{id}")
    @Operation(summary = "Update a lab room (404 LAB_ROOM_NOT_FOUND, 409 LAB_ROOM_CODE_TAKEN)")
    public LabRoomResponse update(@PathVariable Long id, @Valid @RequestBody LabRoomRequest request) {
        return labRoomService.update(id, request, AdminActor.currentUserId());
    }

    @DeleteMapping("/api/v1/admin/lab-rooms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unused lab room (409 LAB_ROOM_IN_USE if assets reference it)")
    public void delete(@PathVariable Long id) {
        labRoomService.delete(id, AdminActor.currentUserId());
    }
}
