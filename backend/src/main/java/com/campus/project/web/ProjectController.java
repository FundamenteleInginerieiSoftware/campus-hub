package com.campus.project.web;

import com.campus.project.domain.Lang;
import com.campus.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No {@code /admin} prefix: students use these endpoints, and {@code SecurityConfig}'s {@code
 * /api/v1/**} rule requires a valid JWT. 401 and {@code ProblemDetail} error schemas are added to
 * Swagger automatically by {@code OpenApiConfig} (#184).
 */
@RestController
@RequestMapping("/api/v1/projects")
@Validated
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Bilingual project library, search, and live equipment availability")
public class ProjectController {

    private final ProjectService projectService;

    // lang is read as a raw String and validated via Lang.parse so an unknown value throws
    // BadRequestException (400 VALIDATION_FAILED) instead of Spring's enum conversion failure
    // (400 INVALID_PARAMETER in Liviu's GlobalExceptionHandler).
    @GetMapping
    @Operation(
            summary = "List or search projects",
            description =
                    "Searches the bilingual (RO/EN) project library and returns matching projects in the requested language (default en).")
    @ApiResponse(responseCode = "200", description = "Matching projects")
    @ApiResponse(
            responseCode = "400",
            description = "Query over 200 characters or lang is not ro/en (VALIDATION_FAILED)")
    public ProjectSearchResponse search(
            @RequestParam(required = false) @Size(max = 200, message = "q must not exceed 200 characters") String q,
            @RequestParam(required = false) String lang) {
        return projectService.search(q, Lang.parse(lang));
    }

    @GetMapping("/{slug}")
    @Operation(
            summary = "Get project details and component availability",
            description =
                    "Returns localized project steps, mistakes, and live component availability from CatalogApi (and ReservationApi when both start and end are given).")
    @ApiResponse(responseCode = "200", description = "Project details and live availability")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Bad lang, only one of start/end given, end not after start, or malformed date-time (VALIDATION_FAILED, INVALID_PARAMETER)")
    @ApiResponse(responseCode = "404", description = "Unknown project slug (PROJECT_NOT_FOUND)")
    public ProjectDetailsResponse details(
            @PathVariable String slug,
            @RequestParam(required = false) String lang,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return projectService.details(slug, Lang.parse(lang), start, end);
    }
}
