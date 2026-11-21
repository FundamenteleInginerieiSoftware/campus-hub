package com.campus.incident.web;

import com.campus.incident.service.ReliabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Admin only through SecurityConfig (/api/v1/admin/**); 401 and 403 are added to every endpoint by OpenApiConfig.
@RestController
@RequestMapping("/api/v1/admin/reliability")
@Validated
@Tag(name = "Equipment reliability", description = "Which units keep breaking (#291)")
public class AdminReliabilityController {

    private final ReliabilityService reliabilityService;

    public AdminReliabilityController(ReliabilityService reliabilityService) {
        this.reliabilityService = reliabilityService;
    }

    @GetMapping
    @Operation(
            summary = "Units with at least one failure in the window, worst first",
            description = "A failure is an incident that was not rejected. INSPECT: at least 3 failures, or at least"
                    + " 2 and failing at least 2x as often as the other units of the same model. WATCH: at least"
                    + " 1 failure. Units without failures are not listed. Rates use at least 10 hours of use.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The report (not paged)"),
        @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: days outside 30-365")
    })
    public ReliabilityReportResponse report(
            @Parameter(description = "Window in days, 30 to 365") @RequestParam(defaultValue = "180") @Min(30) @Max(365)
                    int days) {
        return reliabilityService.report(days);
    }
}
