package com.campus.common.audit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.audit.AuditService;
import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.paging.PageResponse;
import com.campus.config.SecurityConfig;
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

@WebMvcTest(AdminAuditLogController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminAuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    private final AuditLogResponse sampleLog = new AuditLogResponse(
            1L,
            3L,
            "ASSET_RETIRED",
            "ASSET",
            12L,
            "Retired damaged multimeter",
            LocalDateTime.of(2026, 11, 8, 10, 0, 0));

    @Test
    @DisplayName("GET /api/v1/admin/audit-logs returns 401 when anonymous")
    void getAuditLogsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());

        verifyNoInteractions(auditService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/admin/audit-logs returns 403 when user is student")
    void getAuditLogsForbiddenForStudent() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isForbidden());

        verifyNoInteractions(auditService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/audit-logs returns 200 with PageResponse when admin")
    void getAuditLogsSuccessForAdmin() throws Exception {
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(sampleLog), 0, 20, 1, 1);

        when(auditService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].actorId").value(3))
                .andExpect(jsonPath("$.content[0].action").value("ASSET_RETIRED"))
                .andExpect(jsonPath("$.content[0].entityType").value("ASSET"))
                .andExpect(jsonPath("$.content[0].entityId").value(12))
                .andExpect(jsonPath("$.content[0].details").value("Retired damaged multimeter"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-11-08T10:00:00"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/audit-log (singular alias) returns 200 for admin")
    void getAuditLogSingularAliasSuccessForAdmin() throws Exception {
        PageResponse<AuditLogResponse> page = new PageResponse<>(List.of(sampleLog), 0, 20, 1, 1);

        when(auditService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/audit-logs forwards all query filter parameters to AuditService")
    void getAuditLogsForwardsAllFilters() throws Exception {
        PageResponse<AuditLogResponse> emptyPage = new PageResponse<>(List.of(), 0, 20, 0, 0);

        when(auditService.search(
                        eq(5L),
                        eq("USER_ROLE_CHANGED"),
                        eq("USER"),
                        eq(10L),
                        eq(LocalDateTime.of(2026, 11, 1, 0, 0)),
                        eq(LocalDateTime.of(2026, 11, 7, 23, 59)),
                        eq(2),
                        eq(15),
                        eq("createdAt,asc")))
                .thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("actorId", "5")
                        .param("action", "USER_ROLE_CHANGED")
                        .param("entityType", "USER")
                        .param("entityId", "10")
                        .param("from", "2026-11-01T00:00:00")
                        .param("to", "2026-11-07T23:59:00")
                        .param("page", "2")
                        .param("size", "15")
                        .param("sort", "createdAt,asc"))
                .andExpect(status().isOk());

        verify(auditService)
                .search(
                        5L,
                        "USER_ROLE_CHANGED",
                        "USER",
                        10L,
                        LocalDateTime.of(2026, 11, 1, 0, 0),
                        LocalDateTime.of(2026, 11, 7, 23, 59),
                        2,
                        15,
                        "createdAt,asc");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/audit-logs returns 400 INVALID_PARAMETER on malformed timestamp")
    void getAuditLogsInvalidDateFormatReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs").param("from", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'from'"));

        verifyNoInteractions(auditService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/admin/audit-logs converts InvalidParameterException into 400 ProblemDetail")
    void getAuditLogsInvalidParameterHandledByGlobalExceptionHandler() throws Exception {
        when(auditService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new InvalidParameterException("'from' timestamp must not be after 'to' timestamp"));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("from", "2026-11-08T12:00:00")
                        .param("to", "2026-11-07T12:00:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("'from' timestamp must not be after 'to' timestamp"));
    }
}
