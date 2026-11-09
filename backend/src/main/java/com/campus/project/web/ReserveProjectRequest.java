package com.campus.project.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

// lenient = FALSE makes Jackson refuse "2026-11-16T10:00:00Z" or "+02:00" (400 MALFORMED_REQUEST)
// instead of dropping the offset, the same as Calin's reservation requests since #233.
// Campus times never carry an offset.
@Schema(description = "Slot for booking every item of a project kit")
public record ReserveProjectRequest(
        @NotNull(message = "start must not be null")
        @Schema(description = "Start (campus time, no offset)", example = "2026-11-16T10:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime start,

        @NotNull(message = "end must not be null")
        @Schema(description = "End (campus time, no offset)", example = "2026-11-16T12:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime end) {}
