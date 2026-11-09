package com.campus.incident.web;

import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.incident.service.IncidentPhoto;
import com.campus.incident.service.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 401 (and 403 under /admin) are added to every endpoint by OpenApiConfig (#184); only the
// incident-specific errors are listed here. The codes are the ones in api-contract §5 (#189).
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents", description = "Endpoints for reporting equipment incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final CurrentUser currentUser;

    public IncidentController(IncidentService incidentService, CurrentUser currentUser) {
        this.incidentService = incidentService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report an incident on an asset; HIGH and CRITICAL take the asset out of use at once")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Incident saved as OPEN; HIGH/CRITICAL also make the asset UNDER_MAINTENANCE (#229)"),
        @ApiResponse(
                responseCode = "400",
                description = "VALIDATION_FAILED (assetId, description 1-2000 chars, severity) or MALFORMED_REQUEST"),
        @ApiResponse(responseCode = "404", description = "ASSET_NOT_FOUND"),
        @ApiResponse(
                responseCode = "409",
                description = "ASSET_RETIRED, or TOO_MANY_OPEN_INCIDENTS (a student already has 3 OPEN reports): "
                        + "nothing is saved")
    })
    public IncidentResponse reportIncident(@Valid @RequestBody CreateIncidentRequest request) {
        return incidentService.reportIncident(request, currentUser.id(), currentUser.isAdmin());
    }

    // "/me" is a fixed path, so Spring picks it before "/{id}".
    @GetMapping("/me")
    @Operation(summary = "The incidents I reported, newest first")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of my incidents"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_PARAMETER: page < 0, size outside 1-100, page * size too big, or not a number")
    })
    public PageResponse<IncidentResponse> getMyIncidents(
            @Parameter(description = "Page index, from 0") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1 to 100") @RequestParam(value = "size", defaultValue = "20")
                    int size) {
        return incidentService.getMyIncidents(currentUser.id(), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One incident; only its reporter or an admin can see it (anyone else gets 404)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The incident"),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: the id is not a number"),
        @ApiResponse(
                responseCode = "404",
                description = "INCIDENT_NOT_FOUND: no such id, or someone else's incident (ids are not revealed)")
    })
    public IncidentResponse getIncident(@PathVariable("id") Long id) {
        return incidentService.getIncident(id, currentUser.id(), currentUser.isAdmin());
    }

    // multipart/form-data with one part named "file". Files over 5 MB are refused before this runs
    // (spring.servlet.multipart in application.yml → 413 PHOTO_TOO_LARGE); StorageService checks the rest.
    @PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add or replace the photo of an incident (JPEG, PNG or WebP, max 5 MB); reporter or admin")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Photo stored; the incident comes back with its photoKey"),
        @ApiResponse(
                responseCode = "400",
                description = "INVALID_PHOTO_TYPE (not JPEG/PNG/WebP, empty, or the bytes are not an image), "
                        + "BAD_REQUEST (no \"file\" part) or INVALID_PARAMETER (id not a number)"),
        @ApiResponse(responseCode = "404", description = "INCIDENT_NOT_FOUND: no such id, or someone else's incident"),
        @ApiResponse(
                responseCode = "409",
                description = "INVALID_INCIDENT_STATUS_TRANSITION: the incident is RESOLVED or REJECTED"),
        @ApiResponse(responseCode = "413", description = "PHOTO_TOO_LARGE: over 5 MB")
    })
    public IncidentResponse uploadPhoto(@PathVariable("id") Long id, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            return incidentService.attachPhoto(
                    id, content, file.getSize(), file.getContentType(), currentUser.id(), currentUser.isAdmin());
        }
    }

    // A plain <img src> sends no token, so the frontend fetches this with the token and shows a blob URL.
    // nosniff: the browser must use our Content-Type and never guess the bytes are HTML or a script.
    @GetMapping("/{id}/photo")
    @Operation(summary = "The photo of one incident; only its reporter or an admin can see it (anyone else gets 404)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The image bytes (image/jpeg, image/png or image/webp)"),
        @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER: the id is not a number"),
        @ApiResponse(
                responseCode = "404",
                description = "INCIDENT_NOT_FOUND (no such id, or someone else's) or PHOTO_NOT_FOUND (no photo yet)")
    })
    public ResponseEntity<Resource> getPhoto(@PathVariable("id") Long id) {
        IncidentPhoto photo = incidentService.loadPhoto(id, currentUser.id(), currentUser.isAdmin());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.resource());
    }
}
