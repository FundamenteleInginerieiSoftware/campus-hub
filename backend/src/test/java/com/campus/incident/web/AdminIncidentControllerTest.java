package com.campus.incident.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.config.SecurityConfig;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.service.IncidentService;
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

@WebMvcTest(AdminIncidentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminIncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    @MockitoBean
    private CurrentUser currentUser;

    private IncidentResponse withStatus(IncidentStatus status, String notes) {
        return new IncidentResponse(
                100L,
                12L,
                null,
                1L,
                "Display cracked",
                IncidentSeverity.HIGH,
                status,
                null,
                LocalDateTime.of(2026, 11, 3, 14, 0),
                null,
                null,
                notes);
    }

    // ---- GET /api/v1/admin/incidents ---------------------------------------------------------

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET admin list returns 403 for a student")
    void listForbiddenForStudent() throws Exception {
        mockMvc.perform(get("/api/v1/admin/incidents")).andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    @Test
    @DisplayName("GET admin list returns 401 when anonymous")
    void listAnonymousReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/incidents")).andExpect(status().isUnauthorized());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET admin list returns every incident, page 0 of 20 by default")
    void listReturnsPage() throws Exception {
        when(incidentService.getAllIncidents(null, 0, 20))
                .thenReturn(new PageResponse<>(List.of(withStatus(IncidentStatus.OPEN, null)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/admin/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(100))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET admin list passes the status filter, page and size on to the service")
    void listWithFilterAndPage() throws Exception {
        when(incidentService.getAllIncidents(IncidentStatus.IN_REPAIR, 2, 50))
                .thenReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));

        mockMvc.perform(get("/api/v1/admin/incidents")
                        .param("status", "IN_REPAIR")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk());

        verify(incidentService).getAllIncidents(IncidentStatus.IN_REPAIR, 2, 50);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET admin list returns 400 INVALID_PARAMETER for an unknown status")
    void listWithUnknownStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/incidents").param("status", "BROKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET admin list returns 400 INVALID_PARAMETER for a size over 100")
    void listWithTooBigSizeReturns400() throws Exception {
        when(incidentService.getAllIncidents(null, 0, 500))
                .thenThrow(new InvalidParameterException("Page size must be between 1 and 100"));

        mockMvc.perform(get("/api/v1/admin/incidents").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ---- POST .../start-repair ---------------------------------------------------------------

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST start-repair returns 403 for a student")
    void startRepairForbiddenForStudent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/100/start-repair")).andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST start-repair returns 200 with status IN_REPAIR")
    void startRepairReturns200() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.startRepair(100L, 99L)).thenReturn(withStatus(IncidentStatus.IN_REPAIR, null));

        mockMvc.perform(post("/api/v1/admin/incidents/100/start-repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REPAIR"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST start-repair returns 409 INVALID_INCIDENT_STATUS_TRANSITION from RESOLVED")
    void startRepairFromResolvedReturns409() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.startRepair(100L, 99L))
                .thenThrow(new InvalidStateTransitionException(
                        "INVALID_INCIDENT_STATUS_TRANSITION", "Repair cannot start from status: RESOLVED"));

        mockMvc.perform(post("/api/v1/admin/incidents/100/start-repair"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INCIDENT_STATUS_TRANSITION"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST start-repair returns 404 INCIDENT_NOT_FOUND for a missing incident")
    void startRepairMissingReturns404() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.startRepair(404L, 99L))
                .thenThrow(new NotFoundException("INCIDENT_NOT_FOUND", "Incident report not found with id: 404"));

        mockMvc.perform(post("/api/v1/admin/incidents/404/start-repair"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    // ---- POST .../resolve --------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST resolve returns 403 for a student")
    void resolveForbiddenForStudent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/100/resolve")).andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST resolve with notes returns 200 with status RESOLVED, closed by the logged-in admin")
    void resolveWithNotesReturns200() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.resolveIncident(100L, "Replaced panel", 99L))
                .thenReturn(withStatus(IncidentStatus.RESOLVED, "Replaced panel"));

        mockMvc.perform(post("/api/v1/admin/incidents/100/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNotes\":\"Replaced panel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNotes").value("Replaced panel"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST resolve without a body is fine")
    void resolveWithoutBodyReturns200() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.resolveIncident(100L, null, 99L)).thenReturn(withStatus(IncidentStatus.RESOLVED, null));

        mockMvc.perform(post("/api/v1/admin/incidents/100/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST resolve with notes over 2000 characters returns 400 VALIDATION_FAILED")
    void resolveWithTooLongNotesReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/100/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNotes\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST resolve returns 409 INVALID_INCIDENT_STATUS_TRANSITION from REJECTED")
    void resolveFromRejectedReturns409() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.resolveIncident(100L, null, 99L))
                .thenThrow(new InvalidStateTransitionException(
                        "INVALID_INCIDENT_STATUS_TRANSITION", "Incident cannot be resolved from status: REJECTED"));

        mockMvc.perform(post("/api/v1/admin/incidents/100/resolve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INCIDENT_STATUS_TRANSITION"));
    }

    // ---- POST .../reject ---------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST reject returns 403 for a student")
    void rejectForbiddenForStudent() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/100/reject")).andExpect(status().isForbidden());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST reject with notes returns 200 with status REJECTED")
    void rejectWithNotesReturns200() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.rejectIncident(100L, "Works fine", 99L))
                .thenReturn(withStatus(IncidentStatus.REJECTED, "Works fine"));

        mockMvc.perform(post("/api/v1/admin/incidents/100/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNotes\":\"Works fine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolutionNotes").value("Works fine"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST reject without a body is fine")
    void rejectWithoutBodyReturns200() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(incidentService.rejectIncident(100L, null, 99L)).thenReturn(withStatus(IncidentStatus.REJECTED, null));

        mockMvc.perform(post("/api/v1/admin/incidents/100/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST reject with notes over 2000 characters returns 400 VALIDATION_FAILED")
    void rejectWithTooLongNotesReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/100/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNotes\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST reject returns 400 INVALID_PARAMETER for a text id")
    void rejectWithTextIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/incidents/abc/reject"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(incidentService);
    }
}
