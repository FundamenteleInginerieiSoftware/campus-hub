package com.campus.reassignment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.domain.ReassignmentTrigger;
import com.campus.reassignment.service.MaintenanceImpactSimulator;
import com.campus.reassignment.service.ReassignmentService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminReassignmentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminReassignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReassignmentService reassignmentService;

    @MockitoBean
    private MaintenanceImpactSimulator maintenanceImpactSimulator;

    private final ReassignmentLogResponse sampleLog = new ReassignmentLogResponse(
            1L,
            100L,
            ReassignmentTrigger.INCIDENT,
            10L,
            501L,
            502L,
            ReassignmentOutcome.REASSIGNED,
            "SameModelStrategy",
            LocalDateTime.of(2026, 11, 2, 10, 0));

    @Test
    @DisplayName("GET /api/v1/admin/reassignments returns 401 when anonymous")
    void getLogsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reassignments")).andExpect(status().isUnauthorized());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/admin/reassignments returns 403 when user is student")
    void getLogsForbiddenForStudent() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reassignments")).andExpect(status().isForbidden());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/reassignments returns 200 with PageResponse when admin")
    void getLogsSuccessForAdmin() throws Exception {
        PageResponse<ReassignmentLogResponse> page = new PageResponse<>(List.of(sampleLog), 0, 20, 1, 1);

        when(reassignmentService.getLogs(anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/reassignments").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].incidentId").value(100))
                .andExpect(jsonPath("$.content[0].trigger").value("INCIDENT"))
                .andExpect(jsonPath("$.content[0].reservationId").value(10))
                .andExpect(jsonPath("$.content[0].fromAssetId").value(501))
                .andExpect(jsonPath("$.content[0].toAssetId").value(502))
                .andExpect(jsonPath("$.content[0].outcome").value("REASSIGNED"))
                .andExpect(jsonPath("$.content[0].strategy").value("SameModelStrategy"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/reassignments returns 400 when page is negative")
    void getLogsInvalidPageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reassignments").param("page", "-1")).andExpect(status().isBadRequest());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/reassignments returns 400 when size is zero or exceeds 100")
    void getLogsInvalidSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reassignments").param("size", "0")).andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/reassignments").param("size", "101")).andExpect(status().isBadRequest());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/admin/reassignments/simulate returns 403 when user is student")
    void simulateForbiddenForStudent() throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/reassignments/simulate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"assetId\":1,\"windowStart\":\"2026-11-10T09:00:00\",\"windowEnd\":\"2026-11-10T17:00:00\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(maintenanceImpactSimulator);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/admin/reassignments/simulate returns 200 with dry-run result for admin")
    void simulateSuccessForAdmin() throws Exception {
        MaintenanceImpactResponse response = new MaintenanceImpactResponse(
                1L,
                "Scope 1",
                LocalDateTime.of(2026, 11, 10, 9, 0),
                LocalDateTime.of(2026, 11, 10, 17, 0),
                1,
                1,
                0,
                1,
                List.of());
        when(maintenanceImpactSimulator.simulate(any())).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/admin/reassignments/simulate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"assetId\":1,\"windowStart\":\"2026-11-10T09:00:00\",\"windowEnd\":\"2026-11-10T17:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(1))
                .andExpect(jsonPath("$.reassignedCount").value(1))
                .andExpect(jsonPath("$.disruptionScore").value(1));
    }
}
