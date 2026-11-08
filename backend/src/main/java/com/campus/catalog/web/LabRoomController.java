package com.campus.catalog.web;

import com.campus.catalog.service.LabRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

// 401 (and 403 under /admin) are added automatically by OpenApiConfig (#184).
@RestController
@RequiredArgsConstructor
@Tag(name = "Lab Rooms", description = "Laboratory rooms lookup and admin CRUD")
public class LabRoomController {

    private final LabRoomService labRoomService;

    @GetMapping("/api/v1/lab-rooms")
    @Operation(summary = "List all laboratory rooms sorted by code")
    @ApiResponse(responseCode = "200", description = "List of lab rooms")
    public List<LabRoomResponse> listAll() {
        return labRoomService.listAll();
    }

    @PostMapping("/api/v1/admin/lab-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a lab room")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Lab room created"),
        @ApiResponse(responseCode = "400", description = "Validation failed (VALIDATION_FAILED)"),
        @ApiResponse(responseCode = "409", description = "Lab room code already exists (LAB_ROOM_CODE_TAKEN)")
    })
    public LabRoomResponse create(@Valid @RequestBody LabRoomRequest request) {
        return labRoomService.create(request);
    }

    @PutMapping("/api/v1/admin/lab-rooms/{id}")
    @Operation(summary = "Update a lab room")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lab room updated"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or invalid lab room ID (VALIDATION_FAILED, INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Lab room not found (LAB_ROOM_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Lab room code already exists (LAB_ROOM_CODE_TAKEN)")
    })
    public LabRoomResponse update(@PathVariable Long id, @Valid @RequestBody LabRoomRequest request) {
        return labRoomService.update(id, request);
    }

    @DeleteMapping("/api/v1/admin/lab-rooms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unused lab room")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Lab room deleted"),
        @ApiResponse(responseCode = "400", description = "Invalid lab room ID parameter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "404", description = "Lab room not found (LAB_ROOM_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Lab room is referenced by existing assets (LAB_ROOM_IN_USE)")
    })
    public void delete(@PathVariable Long id) {
        labRoomService.delete(id);
    }
}
