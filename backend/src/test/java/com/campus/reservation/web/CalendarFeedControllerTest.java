package com.campus.reservation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.campus.common.error.GlobalExceptionHandler;
import com.campus.common.error.NotFoundException;
import com.campus.reservation.service.CalendarFeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(CalendarFeedController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class CalendarFeedControllerTest {

    private static final String VALID_TOKEN = "aB3dEf9012345678901234567890123456789012XyZ";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private CalendarFeedService calendarFeedService;

    @Test
    void feedNeedsNoLogin() throws Exception {
        String sampleIcs = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n";
        when(calendarFeedService.feedFor(VALID_TOKEN)).thenReturn(sampleIcs);

        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/calendar/{token}.ics", VALID_TOKEN))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as("GET /api/v1/calendar/{token}.ics must answer 200; see CalendarFeedController.feed")
                .isEqualTo(200);
        assertThat(response.getContentType())
                .as("Content-Type must be text/calendar;charset=UTF-8; see CalendarFeedController.feed")
                .isEqualToIgnoringCase("text/calendar;charset=UTF-8");
        assertThat(response.getHeader("Cache-Control"))
                .as(
                        "Cache-Control must include no-store so revoked links are not cached; see CalendarFeedController.feed")
                .contains("no-store");
        assertThat(response.getContentAsString())
                .as("Response body must return the generated iCalendar text; see CalendarFeedController.feed")
                .isEqualTo(sampleIcs);
    }

    @Test
    void unknownTokenIs404WithCode() throws Exception {
        when(calendarFeedService.feedFor(VALID_TOKEN))
                .thenThrow(new NotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found"));

        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/calendar/{token}.ics", VALID_TOKEN))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus())
                .as("Unknown token must answer 404; see CalendarFeedService.feedFor")
                .isEqualTo(404);
        assertThat(jsonMapper
                        .readTree(response.getContentAsString())
                        .path("code")
                        .asString())
                .as("404 ProblemDetail must carry code CALENDAR_FEED_NOT_FOUND; see CalendarFeedService.feedFor")
                .isEqualTo("CALENDAR_FEED_NOT_FOUND");
    }

    @Test
    void oddTokenIs404() throws Exception {
        for (String oddToken : new String[] {"abc", "a".repeat(5000)}) {
            when(calendarFeedService.feedFor(oddToken))
                    .thenThrow(new NotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found"));

            MockHttpServletResponse response = mockMvc.perform(get("/api/v1/calendar/{token}.ics", oddToken))
                    .andReturn()
                    .getResponse();

            assertThat(response.getStatus())
                    .as("Odd token '%s' must answer 404 and never 500; see CalendarFeedController.feed", oddToken)
                    .isEqualTo(404);
            assertThat(jsonMapper
                            .readTree(response.getContentAsString())
                            .path("code")
                            .asString())
                    .as("404 ProblemDetail for odd token must carry code CALENDAR_FEED_NOT_FOUND")
                    .isEqualTo("CALENDAR_FEED_NOT_FOUND");
        }
    }
}
