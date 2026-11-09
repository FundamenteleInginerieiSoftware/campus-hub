package com.campus.project.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.config.SecurityConfig;
import com.campus.project.domain.Difficulty;
import com.campus.project.domain.Lang;
import com.campus.project.service.ProjectReservationService;
import com.campus.project.service.ProjectService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// Web slice only, importing Matei's real SecurityConfig so /api/v1/projects/** is checked
// against the /api/v1/** authenticated rule (same setup as AssetControllerTest and UserControllerTest).
@WebMvcTest(ProjectController.class)
@Import(SecurityConfig.class)
class ProjectControllerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 16, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private ProjectReservationService projectReservationService;

    @Test
    void requiresLogin() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/projects")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "GET /api/v1/projects without a token must return 401 (body: %s); see SecurityConfig.apiChain",
                        response.getContentAsString())
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("unauthenticated request must return ProblemDetail code UNAUTHORIZED; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(projectService);
    }

    @Test
    @WithMockUser
    void searchReturns200() throws Exception {
        ProjectSearchResponse expected = new ProjectSearchResponse(
                false,
                List.of(new ProjectListItem(
                        "thermometer",
                        "Digital thermometer",
                        "Measure ambient temperature",
                        Difficulty.BEGINNER,
                        3,
                        4,
                        3)));
        when(projectService.search(null, Lang.EN)).thenReturn(expected);

        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/projects")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "authenticated GET /api/v1/projects must return 200 (body: %s); see ProjectController.search",
                        response.getContentAsString())
                .isEqualTo(200);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("ambiguous")
                        .asBoolean())
                .as("GET /api/v1/projects must serialize $.ambiguous; see ProjectController.search")
                .isFalse();
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("items")
                        .path(0)
                        .path("slug")
                        .asString())
                .as("GET /api/v1/projects must serialize $.items[0].slug; see ProjectController.search")
                .isEqualTo("thermometer");
    }

    @Test
    @WithMockUser
    void badLangIs400() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/projects").param("lang", "fr"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "GET /api/v1/projects?lang=fr must return 400 (body: %s); see Lang.parse",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("unsupported lang must return ProblemDetail code VALIDATION_FAILED; see Lang.parse")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(projectService);
    }

    @Test
    @WithMockUser
    void rejectsTooLongQuery() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/projects").param("q", "a".repeat(201)))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "query over 200 characters must return 400 (body: %s); see ProjectController.search",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as(
                        "query over 200 characters must return ProblemDetail code VALIDATION_FAILED; see GlobalExceptionHandler.handleConstraintViolation")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(projectService);
    }

    @Test
    @WithMockUser
    void unknownSlugIs404() throws Exception {
        when(projectService.details("unknown", Lang.EN, null, null))
                .thenThrow(new NotFoundException("PROJECT_NOT_FOUND", "Project 'unknown' was not found"));

        MockHttpServletResponse response =
                mockMvc.perform(get("/api/v1/projects/unknown")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "unknown project slug must return 404 (body: %s); see ProjectService.details",
                        response.getContentAsString())
                .isEqualTo(404);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("unknown project slug must return ProblemDetail code PROJECT_NOT_FOUND; see ProjectService.details")
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    @Test
    @WithMockUser
    void detailsPassesSlotThrough() throws Exception {
        ProjectDetailsResponse details = new ProjectDetailsResponse(
                "thermometer",
                "Digital thermometer",
                "Measure ambient temperature",
                Difficulty.BEGINNER,
                3,
                List.of(new ComponentView("Arduino Uno R3", 1, "Main controller", 2)),
                List.of("USB-B cable"),
                List.of(new StepView("Wire sensor", "Connect DQ to D2")),
                List.of("Forgetting the pull-up resistor"),
                true,
                true);
        when(projectService.details("thermometer", Lang.EN, START, END)).thenReturn(details);

        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/projects/thermometer")
                        .param("start", "2026-11-16T10:00:00")
                        .param("end", "2026-11-16T12:00:00"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "GET /api/v1/projects/thermometer with slot must return 200 (body: %s); see ProjectController.details",
                        response.getContentAsString())
                .isEqualTo(200);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("slug")
                        .asString())
                .as("GET /api/v1/projects/{slug} must serialize $.slug; see ProjectController.details")
                .isEqualTo("thermometer");
        verify(projectService).details("thermometer", Lang.EN, START, END);
    }

    @Test
    @WithMockUser
    void malformedSlotDateTimeIs400() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/projects/thermometer")
                        .param("start", "not-a-date")
                        .param("end", "2026-11-16T12:00:00"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "malformed ISO date-time on start must return 400 (body: %s); see ProjectController.details",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as(
                        "malformed start parameter must return ProblemDetail code INVALID_PARAMETER; see GlobalExceptionHandler.handleTypeMismatch")
                .isEqualTo("INVALID_PARAMETER");
        verifyNoInteractions(projectService);
    }

    @Test
    @WithMockUser
    void rejectsTimeZoneOffsetOnSlotParams() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/projects/thermometer")
                        .param("start", "2026-11-16T10:00:00Z")
                        .param("end", "2026-11-16T12:00:00"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as(
                        "ISO date-time with timezone offset on start must return 400 (body: %s); see ProjectController.details",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as(
                        "timezone offset on start must return ProblemDetail code INVALID_PARAMETER; see ProjectController.details")
                .isEqualTo("INVALID_PARAMETER");
        verifyNoInteractions(projectService);
    }

    private static final String SLOT_BODY = "{\"start\":\"2026-11-16T10:00:00\",\"end\":\"2026-11-16T12:00:00\"}";

    private MockHttpServletResponse postReserve(String slug, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + slug + "/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).path("code").asString();
    }

    @Test
    @WithMockUser
    void reserveReturns201WithBundle() throws Exception {
        UUID bundleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(projectReservationService.reserve("oscilloscope-pwm", START, END))
                .thenReturn(new ReserveProjectResponse(
                        bundleId, START, END, List.of(new ReservedItem(4L, "Rigol DS1054Z #1", "DS1054Z"))));

        MockHttpServletResponse response = postReserve("oscilloscope-pwm", SLOT_BODY);

        assertThat(response.getStatus())
                .as(
                        "POST /api/v1/projects/{slug}/reserve must return 201 (body: %s); see ProjectController.reserve",
                        response.getContentAsString())
                .isEqualTo(201);
        var json = jsonMapper.readTree(response.getContentAsString());
        assertThat(json.path("bundleId").asString())
                .as("reserve response must serialize $.bundleId; see ReserveProjectResponse")
                .isEqualTo(bundleId.toString());
        assertThat(json.path("start").asString())
                .as("reserve response must serialize $.start without an offset; see ReserveProjectResponse")
                .isEqualTo("2026-11-16T10:00:00");
        assertThat(json.path("items").path(0).path("assetId").asLong())
                .as("reserve response must serialize $.items[0].assetId; see ReservedItem")
                .isEqualTo(4L);
        assertThat(json.path("items").path(0).path("assetName").asString())
                .as("reserve response must serialize $.items[0].assetName; see ReservedItem")
                .isEqualTo("Rigol DS1054Z #1");
        assertThat(json.path("items").path(0).path("model").asString())
                .as("reserve response must serialize $.items[0].model; see ReservedItem")
                .isEqualTo("DS1054Z");
    }

    @Test
    @WithMockUser
    void reserveWithoutEndIs400() throws Exception {
        MockHttpServletResponse response = postReserve("oscilloscope-pwm", "{\"start\":\"2026-11-16T10:00:00\"}");

        assertThat(response.getStatus())
                .as(
                        "reserve without end must return 400 (body: %s); see ReserveProjectRequest @NotNull",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(code(response))
                .as("missing end must return ProblemDetail code VALIDATION_FAILED; see ReserveProjectRequest")
                .isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(projectReservationService);
    }

    @Test
    @WithMockUser
    void reserveRejectsTimeZoneOffset() throws Exception {
        MockHttpServletResponse response = postReserve(
                "oscilloscope-pwm", "{\"start\":\"2026-11-16T10:00:00Z\",\"end\":\"2026-11-16T12:00:00Z\"}");

        assertThat(response.getStatus())
                .as(
                        "reserve with a Z offset must return 400 (body: %s); see ReserveProjectRequest @JsonFormat(lenient = FALSE)",
                        response.getContentAsString())
                .isEqualTo(400);
        assertThat(code(response))
                .as("an offset in the body must return MALFORMED_REQUEST, like reservation requests since #233; "
                        + "see ReserveProjectRequest")
                .isEqualTo("MALFORMED_REQUEST");
        verifyNoInteractions(projectReservationService);
    }

    @Test
    void reserveRequiresLogin() throws Exception {
        MockHttpServletResponse response = postReserve("oscilloscope-pwm", SLOT_BODY);

        assertThat(response.getStatus())
                .as(
                        "reserve without a token must return 401 (body: %s); see SecurityConfig.apiChain",
                        response.getContentAsString())
                .isEqualTo(401);
        verifyNoInteractions(projectReservationService);
    }

    @Test
    @WithMockUser
    void reserveUnknownSlugIs404() throws Exception {
        when(projectReservationService.reserve("unknown", START, END))
                .thenThrow(new NotFoundException("PROJECT_NOT_FOUND", "Project 'unknown' was not found"));

        MockHttpServletResponse response = postReserve("unknown", SLOT_BODY);

        assertThat(response.getStatus())
                .as(
                        "reserve on an unknown slug must return 404 (body: %s); see ProjectReservationService.reserve",
                        response.getContentAsString())
                .isEqualTo(404);
        assertThat(code(response))
                .as("unknown slug must return ProblemDetail code PROJECT_NOT_FOUND; see ProjectService.findProject")
                .isEqualTo("PROJECT_NOT_FOUND");
    }

    @Test
    @WithMockUser
    void reserveShortComponentIs409() throws Exception {
        when(projectReservationService.reserve("oscilloscope-pwm", START, END))
                .thenThrow(new ConflictException(
                        "PROJECT_COMPONENT_UNAVAILABLE", "Not enough free units of 'DS1054Z' in this slot"));

        MockHttpServletResponse response = postReserve("oscilloscope-pwm", SLOT_BODY);

        assertThat(response.getStatus())
                .as(
                        "a short component must return 409 (body: %s); see ProjectReservationService.pickUnits",
                        response.getContentAsString())
                .isEqualTo(409);
        assertThat(code(response))
                .as("a short component must return ProblemDetail code PROJECT_COMPONENT_UNAVAILABLE; "
                        + "see ProjectReservationService.pickUnits")
                .isEqualTo("PROJECT_COMPONENT_UNAVAILABLE");
    }
}
