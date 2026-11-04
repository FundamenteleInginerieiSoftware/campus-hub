package com.campus.reassignment.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.service.ReassignmentService;
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
}
