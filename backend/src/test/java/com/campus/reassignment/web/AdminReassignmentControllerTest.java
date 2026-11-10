package com.campus.reassignment.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.ConflictException;
import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.service.ReassignmentService;
import com.campus.reassignment.service.optimal.GreedyComparisonSummary;
import com.campus.reassignment.service.optimal.OptimalReassignmentPlan;
import com.campus.reassignment.service.optimal.ReassignmentActionItem;
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

@WebMvcTest(AdminReassignmentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminReassignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReassignmentService reassignmentService;

    private final ReassignmentLogResponse sampleLog = new ReassignmentLogResponse(
            1L,
            100L,
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
    @DisplayName("POST /api/v1/admin/reassignments/solve-optimal returns 401 when anonymous")
    void solveOptimalUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reassignments/solve-optimal").param("assetId", "10"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/admin/reassignments/solve-optimal returns 403 when student")
    void solveOptimalForbiddenForStudent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reassignments/solve-optimal").param("assetId", "10"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reassignmentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/admin/reassignments/solve-optimal returns 200 with optimal plan for admin")
    void solveOptimalSuccessForAdmin() throws Exception {
        OptimalReassignmentPlan response = new OptimalReassignmentPlan(
                10L,
                "Rigol DS1054Z",
                2,
                2,
                0,
                1,
                1,
                false,
                List.of(new ReassignmentActionItem(
                        101L,
                        LocalDateTime.of(2026, 11, 15, 10, 0),
                        LocalDateTime.of(2026, 11, 15, 12, 0),
                        10L,
                        20L,
                        "Substitute Scope",
                        "SameModel",
                        10)),
                new GreedyComparisonSummary(2, 1, 0, 1, 60, 1010, true, "Optimal saved strictly more"));

        when(reassignmentService.solveOptimalPlan(eq(10L), eq(55L), eq(true))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/reassignments/solve-optimal")
                        .param("assetId", "10")
                        .param("incidentId", "55")
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.damagedAssetId").value(10))
                .andExpect(jsonPath("$.totalReservations").value(2))
                .andExpect(jsonPath("$.rescuedReservations").value(2))
                .andExpect(jsonPath("$.forceCancelledReservations").value(0))
                .andExpect(jsonPath("$.comparison.optimalStrictlyBetter").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName(
            "POST /api/v1/admin/reassignments/solve-optimal defaults dryRun to true so a bare call changes nothing")
    void solveOptimalDefaultsToDryRun() throws Exception {
        OptimalReassignmentPlan response = new OptimalReassignmentPlan(
                10L,
                "Rigol DS1054Z",
                2,
                2,
                0,
                1,
                1,
                false,
                List.of(new ReassignmentActionItem(
                        101L,
                        LocalDateTime.of(2026, 11, 15, 10, 0),
                        LocalDateTime.of(2026, 11, 15, 12, 0),
                        10L,
                        20L,
                        "Substitute Scope",
                        "SameModel",
                        10)),
                new GreedyComparisonSummary(2, 1, 0, 1, 60, 1010, true, "Optimal saved strictly more"));

        when(reassignmentService.solveOptimalPlan(eq(10L), any(), eq(true))).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/reassignments/solve-optimal").param("assetId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.damagedAssetId").value(10));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/v1/admin/reassignments/solve-optimal returns 409 when the asset is not UNDER_MAINTENANCE")
    void solveOptimalReturns409ForHealthyAsset() throws Exception {
        when(reassignmentService.solveOptimalPlan(eq(10L), any(), eq(false)))
                .thenThrow(new ConflictException("ASSET_NOT_UNDER_MAINTENANCE", "Asset #10 is not UNDER_MAINTENANCE"));

        mockMvc.perform(post("/api/v1/admin/reassignments/solve-optimal")
                        .param("assetId", "10")
                        .param("dryRun", "false"))
                .andExpect(status().isConflict());
    }
}
