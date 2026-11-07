package com.campus.incident.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(IncidentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class IncidentControllerTest {

    private static final String VALID_REPORT =
            "{\"assetId\": 12, \"description\": \"Display cracked\", \"severity\": \"HIGH\"}";

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

    // ---- POST /api/v1/incidents --------------------------------------------------------------

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents returns 201 with the new incident, reported by the logged-in user")
    void reportReturns201() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.reportIncident(any(CreateIncidentRequest.class), eq(1L)))
                .thenReturn(sample);

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REPORT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.assetId").value(12))
                .andExpect(jsonPath("$.reportedById").value(1))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        verify(incidentService)
                .reportIncident(eq(new CreateIncidentRequest(12L, "Display cracked", IncidentSeverity.HIGH)), eq(1L));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents returns 400 VALIDATION_FAILED for a missing asset, description and severity")
    void reportWithEmptyBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents returns 400 VALIDATION_FAILED for a description over 2000 characters")
    void reportWithTooLongDescriptionReturns400() throws Exception {
        String body = "{\"assetId\": 12, \"description\": \"" + "x".repeat(2001) + "\", \"severity\": \"LOW\"}";

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents returns 409 ASSET_RETIRED for a retired asset")
    void reportOnRetiredAssetReturns409() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.reportIncident(any(CreateIncidentRequest.class), eq(1L)))
                .thenThrow(new InvalidStateTransitionException("ASSET_RETIRED", "Asset is retired"));

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REPORT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_RETIRED"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents returns 401 when anonymous")
    void reportAnonymousReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REPORT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(incidentService);
    }

    // ---- GET /api/v1/incidents/me ------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/incidents/me returns 401 when anonymous")
    void myIncidentsAnonymousReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/me")).andExpect(status().isUnauthorized());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/me returns the logged-in user's incidents, page 0 of 20 by default")
    void myIncidentsReturnsPage() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.getMyIncidents(1L, 0, 20)).thenReturn(new PageResponse<>(List.of(sample), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/incidents/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(100))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/me passes page and size on to the service")
    void myIncidentsPassesPageAndSize() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.getMyIncidents(1L, 3, 5)).thenReturn(new PageResponse<>(List.of(), 3, 5, 0, 0));

        mockMvc.perform(get("/api/v1/incidents/me").param("page", "3").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(3));

        verify(incidentService).getMyIncidents(1L, 3, 5);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/me returns 400 INVALID_PARAMETER for a huge page")
    void myIncidentsWithHugePageReturns400() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.getMyIncidents(1L, 999999999, 100))
                .thenThrow(new InvalidParameterException("Page offset exceeds maximum supported value"));

        mockMvc.perform(get("/api/v1/incidents/me").param("page", "999999999").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/me returns 400 INVALID_PARAMETER when page is not a number")
    void myIncidentsWithTextPageReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/me").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        verifyNoInteractions(incidentService);
    }

    // ---- GET /api/v1/incidents/{id} ----------------------------------------------------------

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
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/incidents/{id} tells the service that the caller is an admin")
    void getIncidentAsAdminPassesTheRole() throws Exception {
        when(currentUser.id()).thenReturn(99L);
        when(currentUser.isAdmin()).thenReturn(true);
        when(incidentService.getIncident(100L, 99L, true)).thenReturn(sample);

        mockMvc.perform(get("/api/v1/incidents/100")).andExpect(status().isOk());

        verify(incidentService).getIncident(100L, 99L, true);
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
