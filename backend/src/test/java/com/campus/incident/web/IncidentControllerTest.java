package com.campus.incident.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.config.SecurityConfig;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.service.IncidentPhoto;
import com.campus.incident.service.IncidentService;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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

    // ---- photo (#171) ------------------------------------------------------------------------

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String PHOTO_KEY = "22222222-2222-2222-2222-222222222222.png";

    private final MockMultipartFile pngFile = new MockMultipartFile("file", "crack.png", "image/png", PNG_BYTES);

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents/{id}/photo returns 200 with the photoKey, for the logged-in user")
    void uploadPhotoReturns200() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(currentUser.isAdmin()).thenReturn(false);
        IncidentResponse withPhoto = new IncidentResponse(
                100L,
                12L,
                null,
                1L,
                "Display cracked",
                IncidentSeverity.HIGH,
                IncidentStatus.OPEN,
                PHOTO_KEY,
                LocalDateTime.of(2026, 11, 3, 14, 0),
                null,
                null,
                null);
        when(incidentService.attachPhoto(
                        eq(100L),
                        any(InputStream.class),
                        eq((long) PNG_BYTES.length),
                        eq("image/png"),
                        eq(1L),
                        eq(false)))
                .thenReturn(withPhoto);

        mockMvc.perform(multipart("/api/v1/incidents/100/photo").file(pngFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.photoKey").value(PHOTO_KEY));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents/{id}/photo returns 400 INVALID_PHOTO_TYPE when storage refuses the file")
    void uploadWrongTypeReturns400() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.attachPhoto(eq(100L), any(InputStream.class), anyLong(), any(), eq(1L), eq(false)))
                .thenThrow(new BadRequestException("INVALID_PHOTO_TYPE", "Only JPEG, PNG or WebP images are allowed"));

        mockMvc.perform(multipart("/api/v1/incidents/100/photo")
                        .file(new MockMultipartFile("file", "page.html", "text/html", "<script>".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO_TYPE"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents/{id}/photo returns 400 without a \"file\" part, before the service runs")
    void uploadWithoutFilePartReturns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/incidents/100/photo")
                        .file(new MockMultipartFile("image", "crack.png", "image/png", PNG_BYTES)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("POST /api/v1/incidents/{id}/photo returns 409 on a closed incident")
    void uploadToClosedIncidentReturns409() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(incidentService.attachPhoto(eq(100L), any(InputStream.class), anyLong(), any(), eq(1L), eq(false)))
                .thenThrow(new InvalidStateTransitionException(
                        "INVALID_INCIDENT_STATUS_TRANSITION", "Photo cannot change when the incident is RESOLVED"));

        mockMvc.perform(multipart("/api/v1/incidents/100/photo").file(pngFile))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_INCIDENT_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{id}/photo returns 401 without a token")
    void uploadWithoutTokenReturns401() throws Exception {
        mockMvc.perform(multipart("/api/v1/incidents/100/photo").file(pngFile)).andExpect(status().isUnauthorized());

        verifyNoInteractions(incidentService);
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/{id}/photo returns the bytes with their type, no-store and nosniff")
    void getPhotoReturnsTheImage() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(incidentService.loadPhoto(100L, 1L, false))
                .thenReturn(new IncidentPhoto(new ByteArrayResource(PNG_BYTES), "image/png"));

        mockMvc.perform(get("/api/v1/incidents/100/photo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG_BYTES))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("GET /api/v1/incidents/{id}/photo returns 404 PHOTO_NOT_FOUND when there is no photo")
    void getMissingPhotoReturns404() throws Exception {
        when(currentUser.id()).thenReturn(1L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(incidentService.loadPhoto(100L, 1L, false))
                .thenThrow(new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found"));

        mockMvc.perform(get("/api/v1/incidents/100/photo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO_NOT_FOUND"));
    }
}
