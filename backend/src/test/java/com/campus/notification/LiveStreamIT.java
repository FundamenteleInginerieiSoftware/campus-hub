package com.campus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.notification.service.LiveStreams;
import com.campus.notification.web.LiveEvent;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class LiveStreamIT {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 15, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LiveStreams liveStreams;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String createdEmail;

    @AfterEach
    void cleanUp() {
        if (createdEmail != null) {
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", createdEmail);
            createdEmail = null;
        }
    }

    @Test
    void openStreamReceivesOwnEventsOnlyAndCompletesCleanly() throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36);
        createdEmail = "live-it-" + run + "@campus.test";

        String registerBody = """
                {
                  "email": "%s",
                  "password": "password123",
                  "fullName": "Live Stream Student"
                }
                """.formatted(createdEmail);

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        long student1Id = ((Number) JsonPath.read(regResult.getResponse().getContentAsString(), "$.id")).longValue();
        long otherUserId = student1Id + 9999L;

        String loginBody = """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(createdEmail);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        String token = JsonPath.read(loginResult.getResponse().getContentAsString(), "$.accessToken");

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/live/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        liveStreams.send(student1Id, LiveEvent.notification("T1", "for one", NOW));
        liveStreams.send(otherUserId, LiveEvent.notification("T2", "for two", NOW));

        String streamedSoFar = asyncResult.getResponse().getContentAsString();
        assertThat(streamedSoFar)
                .as("stream must contain event:notification and student 1's message; see LiveStreams.send")
                .contains("event:notification")
                .contains("for one");
        assertThat(streamedSoFar)
                .as("stream must not leak another user's notification; see LiveStreams.send")
                .doesNotContain("for two");

        // Complete the emitter so Spring MVC performs the ASYNC dispatch; verifies no 401/403
        // (AccessDeniedException) occurs when the async response finishes.
        liveStreams.completeAll(student1Id);
        mockMvc.perform(asyncDispatch(asyncResult)).andExpect(status().isOk());
    }
}
