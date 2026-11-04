package com.campus.incident.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.GlobalExceptionHandler;
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
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IncidentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    // CurrentUser reads a real JWT, which @WithMockUser does not have, so the test decides who is logged in.
    @MockitoBean
    private CurrentUser currentUser;

    private final IncidentResponse sample = new IncidentResponse(
            100L,
            12L,
            null,
            1L,
            "Display cracked",
            IncidentSeverity.HIGH,
            IncidentStatus.OPEN,
            null,
            LocalDateTime.of(2026, 11, 3, 14, 0),
            null,
            null,
            null);

    @Test
    @DisplayName("GET /api/v1/incidents/me returns 401 when anonymous")
    void myIncidentsAnonymousReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/me")).andExpect(status().isUnauthorized());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/me returns the logged-in user's incidents")
    void myIncidentsReturnsPage() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.getMyIncidents(eq(1L), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(sample), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/incidents/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(100))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));

        verify(incidentService).getMyIncidents(eq(1L), any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/{id} returns the incident for its reporter")
    void getIncidentReturns200() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(incidentService.getIncident(100L, 1L, false)).thenReturn(sample);

        mockMvc.perform(get("/api/v1/incidents/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.description").value("Display cracked"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/{id} returns 404 INCIDENT_NOT_FOUND for someone else's incident")
    void getIncidentOfAnotherUserReturns404() throws Exception {
        when(currentUser.id()).thenReturn(7L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(incidentService.getIncident(100L, 7L, false))
                .thenThrow(new NotFoundException("INCIDENT_NOT_FOUND", "Incident report not found with id: 100"));

        mockMvc.perform(get("/api/v1/incidents/100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/abc returns 400 INVALID_PARAMETER")
    void getIncidentWithTextIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(incidentService);
    }
}
