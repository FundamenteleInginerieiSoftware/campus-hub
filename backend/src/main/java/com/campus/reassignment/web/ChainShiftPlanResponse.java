package com.campus.reassignment.web;

import com.campus.reassignment.service.chain.ChainShiftStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Result of chain-shifting conflict resolution using augmenting path search")
public record ChainShiftPlanResponse(
        @Schema(description = "ID of the target equipment requested")
        Long targetAssetId,

        @Schema(description = "Start of the requested reservation window")
        LocalDateTime startTime,

        @Schema(description = "End of the requested reservation window")
        LocalDateTime endTime,

        @Schema(description = "True if an augmenting path of shifts successfully freed the target equipment")
        boolean resolved,

        @Schema(description = "True if the plan was applied to the live database, false if dry-run simulation")
        boolean applied,

        @Schema(description = "Sequence of reservation shifts in execution order")
        List<ChainShiftStep> steps,

        @Schema(description = "Human-readable explanation of the augmenting path outcome")
        String message) {}
