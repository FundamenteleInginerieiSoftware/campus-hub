package com.campus.reservation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.config.SecurityConfig;
import com.campus.reservation.service.CalendarTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(CalendarFeedSettingsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CalendarFeedSettingsControllerTest {

    private static final String BASE_PATH = "/api/v1/reservations/me/calendar-feed";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private CalendarTokenService calendarTokenService;

    @Test
    void requiresLogin() throws Exception {
        MockHttpServletResponse getResponse =
                mockMvc.perform(get(BASE_PATH)).andReturn().getResponse();
        MockHttpServletResponse postResponse =
                mockMvc.perform(post(BASE_PATH)).andReturn().getResponse();
        MockHttpServletResponse deleteResponse =
                mockMvc.perform(delete(BASE_PATH)).andReturn().getResponse();

        assertThat(getResponse.getStatus())
                .as("GET %s without JWT must answer 401; see SecurityConfig.apiChain", BASE_PATH)
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(getResponse.getContentAsString())
                        .path("code")
                        .asString())
                .as("401 ProblemDetail must include code UNAUTHORIZED; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");

        assertThat(postResponse.getStatus())
                .as("POST %s without JWT must answer 401; see SecurityConfig.apiChain", BASE_PATH)
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(postResponse.getContentAsString())
                        .path("code")
                        .asString())
                .as("401 ProblemDetail must include code UNAUTHORIZED; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");

        assertThat(deleteResponse.getStatus())
                .as("DELETE %s without JWT must answer 401; see SecurityConfig.apiChain", BASE_PATH)
                .isEqualTo(401);
        assertThat(jsonMapper
                        .readTree(deleteResponse.getContentAsString())
                        .path("code")
                        .asString())
                .as("401 ProblemDetail must include code UNAUTHORIZED; see SecurityErrorHandler")
                .isEqualTo("UNAUTHORIZED");

        verifyNoInteractions(calendarTokenService);
    }

    @Test
    void statusIs200() throws Exception {
        when(calendarTokenService.status()).thenReturn(new CalendarFeedStatusResponse(true));

        MockHttpServletResponse response =
                mockMvc.perform(get(BASE_PATH).with(jwt())).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "GET %s with JWT must answer 200 (body: %s); see CalendarFeedSettingsController.status",
                        BASE_PATH, response.getContentAsString())
                .isEqualTo(200);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("enabled").asBoolean())
                .as("enabled flag must match CalendarTokenService.status(); see CalendarFeedSettingsController.status")
                .isTrue();
    }

    @Test
    void createIs201WithPath() throws Exception {
        String expectedPath = "/api/v1/calendar/" + "x".repeat(43) + ".ics";
        when(calendarTokenService.create()).thenReturn(new CalendarFeedCreatedResponse(expectedPath));

        MockHttpServletResponse response =
                mockMvc.perform(post(BASE_PATH).with(jwt())).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "POST %s with JWT must answer 201 (body: %s); see CalendarFeedSettingsController.create",
                        BASE_PATH, response.getContentAsString())
                .isEqualTo(201);
        JsonNode body = jsonMapper.readTree(response.getContentAsString());
        assertThat(body.path("path").asString())
                .as("path must match CalendarTokenService.create(); see CalendarFeedSettingsController.create")
                .isEqualTo(expectedPath);
    }

    @Test
    void revokeIs204() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(delete(BASE_PATH).with(jwt())).andReturn().getResponse();

        assertThat(response.getStatus())
                .as(
                        "DELETE %s with JWT must answer 204 (body: %s); see CalendarFeedSettingsController.revoke",
                        BASE_PATH, response.getContentAsString())
                .isEqualTo(204);
        verify(calendarTokenService).revoke();
    }
}
