package com.campus.incident.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.security.CurrentUser;
import com.campus.config.SecurityConfig;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.service.IncidentService;
import java.time.LocalDateTime;
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
        when(incidentService.startRepair(100L)).thenReturn(withStatus(IncidentStatus.IN_REPAIR, null));

        mockMvc.perform(post("/api/v1/admin/incidents/100/start-repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REPAIR"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST start-repair returns 409 INVALID_INCIDENT_STATUS_TRANSITION from RESOLVED")
    void startRepairFromResolvedReturns409() throws Exception {
        when(incidentService.startRepair(100L))
                .thenThrow(new InvalidStateTransitionException(
                        "INVALID_INCIDENT_STATUS_TRANSITION", "Repair cannot start from status: RESOLVED"));

        mockMvc.perform(post("/api/v1/admin/incidents/100/start-repair"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INCIDENT_STATUS_TRANSITION"));
    }

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
        String notes = "x".repeat(2001);

        mockMvc.perform(post("/api/v1/admin/incidents/100/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNotes\":\"" + notes + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(incidentService);
    }
}
