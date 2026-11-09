package com.campus.reservation.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidParameterException;
import com.campus.config.SecurityConfig;
import com.campus.reservation.service.ReservationAnalyticsService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAnalyticsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationAnalyticsService analyticsService;

    private final UtilizationAnalyticsResponse sampleUtilization = new UtilizationAnalyticsResponse(
            LocalDateTime.of(2026, 10, 1, 8, 0),
            LocalDateTime.of(2026, 10, 31, 20, 0),
            31,
            120.0,
            744.0,
            16.1,
            50,
            8.0,
            4.0,
            2,
            0,
            List.of(),
            List.of());

    private final HeatmapResponse sampleHeatmap = new HeatmapResponse(
            LocalDateTime.of(2026, 10, 1, 8, 0),
            LocalDateTime.of(2026, 10, 31, 20, 0),
            null,
            40,
            5,
            List.of(new HeatmapCell(1, 10, 5, 1.0)));

    @Test
    @DisplayName("GET /api/v1/admin/analytics/utilization returns 401 UNAUTHORIZED when no token is provided")
    void getUtilization_unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/utilization"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/admin/analytics/utilization returns 403 FORBIDDEN for students")
    void getUtilization_studentReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/utilization"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/analytics/utilization returns 200 OK for ADMIN")
    void getUtilization_adminReturns200() throws Exception {
        when(analyticsService.getUtilization(any(), any())).thenReturn(sampleUtilization);

        mockMvc.perform(get("/api/v1/admin/analytics/utilization")
                        .param("from", "2026-10-01T08:00:00")
                        .param("to", "2026-10-31T20:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportingDays").value(31))
                .andExpect(jsonPath("$.totalBookedHours").value(120.0))
                .andExpect(jsonPath("$.cancellationRate").value(8.0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName(
            "GET /api/v1/admin/analytics/utilization returns 400 INVALID_PARAMETER when timestamp contains timezone offset (Z or numeric)")
    void getUtilization_rejectsTimezoneOffset() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/utilization").param("from", "2026-10-01T08:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mockMvc.perform(get("/api/v1/admin/analytics/utilization").param("to", "2026-10-31T20:00:00+02:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/analytics/utilization returns 400 INVALID_PARAMETER when from is after to")
    void getUtilization_returns400WhenFromAfterTo() throws Exception {
        when(analyticsService.getUtilization(any(), any()))
                .thenThrow(new InvalidParameterException("from must not be after to"));

        mockMvc.perform(get("/api/v1/admin/analytics/utilization")
                        .param("from", "2026-11-10T10:00:00")
                        .param("to", "2026-11-01T10:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("from must not be after to"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/admin/analytics/heatmap returns 403 FORBIDDEN for students")
    void getHeatmap_studentReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/analytics/heatmap"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(analyticsService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/analytics/heatmap returns 200 OK for ADMIN")
    void getHeatmap_adminReturns200() throws Exception {
        when(analyticsService.getHeatmap(any(), any(), any())).thenReturn(sampleHeatmap);

        mockMvc.perform(get("/api/v1/admin/analytics/heatmap")
                        .param("from", "2026-10-01T08:00:00")
                        .param("to", "2026-10-31T20:00:00")
                        .param("assetId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peakHourlyDemand").value(5))
                .andExpect(jsonPath("$.totalBookingsSampled").value(40))
                .andExpect(jsonPath("$.cells[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.cells[0].hour").value(10))
                .andExpect(jsonPath("$.cells[0].intensityScore").value(1.0));
    }
}
