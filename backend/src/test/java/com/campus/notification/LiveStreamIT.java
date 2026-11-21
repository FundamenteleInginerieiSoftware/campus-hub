package com.campus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.notification.api.NotificationService;
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
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String createdEmail;
    private String createdAdminEmail;
    private Long createdAdminId;
    private Long openedUserId;

    @AfterEach
    void cleanUp() {
        if (openedUserId != null) {
            liveStreams.completeAll(openedUserId);
            openedUserId = null;
        }
        if (createdAdminId != null) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", createdAdminId);
            createdAdminId = null;
        }
        if (createdEmail != null) {
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", createdEmail);
            createdEmail = null;
        }
        if (createdAdminEmail != null) {
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", createdAdminEmail);
            createdAdminEmail = null;
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
        openedUserId = student1Id;
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
                .as(
                        "open() must flush :connected immediately and send() must deliver event:notification; see LiveStreams")
                .contains(":connected")
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

    @Test
    void notifyUserReachesTheOpenStream() throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36) + "b";
        createdEmail = "live-push-it-" + run + "@campus.test";

        String registerBody = """
                {
                  "email": "%s",
                  "password": "password123",
                  "fullName": "Live Push Student"
                }
                """.formatted(createdEmail);

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();

        long student1Id = ((Number) JsonPath.read(regResult.getResponse().getContentAsString(), "$.id")).longValue();
        openedUserId = student1Id;
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

        notificationService.notifyUser(student1Id, "Subject A", "Body A");
        notificationService.notifyUser(otherUserId, "Subject B", "Body B");

        String streamed = asyncResult.getResponse().getContentAsString();
        assertThat(streamed)
                .as(
                        "NotificationService.notifyUser must reach the user's open SSE stream; see LoggingNotificationService")
                .contains("Subject A")
                .contains("Body A");
        assertThat(streamed)
                .as("NotificationService.notifyUser for another user must not leak; see LiveStreams.send")
                .doesNotContain("Subject B")
                .doesNotContain("Body B");

        liveStreams.completeAll(student1Id);
        mockMvc.perform(asyncDispatch(asyncResult)).andExpect(status().isOk());
    }

    @Test
    void disablingUserClosesOpenStreamsAndBlocksReconnect() throws Exception {
        String run = Long.toString(System.currentTimeMillis(), 36) + "c";
        createdEmail = "live-disable-student-" + run + "@campus.test";
        createdAdminEmail = "live-disable-admin-" + run + "@campus.test";

        MvcResult studentReg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123",
                                  "fullName": "Disabled Student"
                                }
                                """.formatted(createdEmail)))
                .andExpect(status().isCreated())
                .andReturn();
        long studentId = ((Number) JsonPath.read(studentReg.getResponse().getContentAsString(), "$.id")).longValue();
        openedUserId = studentId;

        MvcResult adminReg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password123",
                                  "fullName": "Live Test Admin"
                                }
                                """.formatted(createdAdminEmail)))
                .andExpect(status().isCreated())
                .andReturn();
        long adminId = ((Number) JsonPath.read(adminReg.getResponse().getContentAsString(), "$.id")).longValue();
        createdAdminId = adminId;
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", adminId);

        MvcResult studentLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "password123" }
                                """.formatted(createdEmail)))
                .andExpect(status().isOk())
                .andReturn();
        String studentToken = JsonPath.read(studentLogin.getResponse().getContentAsString(), "$.accessToken");

        MvcResult adminLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "password123" }
                                """.formatted(createdAdminEmail)))
                .andExpect(status().isOk())
                .andReturn();
        String adminToken = JsonPath.read(adminLogin.getResponse().getContentAsString(), "$.accessToken");

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/live/stream")
                        .header("Authorization", "Bearer " + studentToken)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(liveStreams.openCount(studentId))
                .as("stream must be registered before disable; see LiveStreams.open")
                .isEqualTo(1);

        mockMvc.perform(post("/api/v1/admin/users/" + studentId + "/disable")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        assertThat(liveStreams.openCount(studentId))
                .as("disabling a user must close all open SSE streams after commit; see UserDisabledLiveStreamListener")
                .isZero();

        mockMvc.perform(asyncDispatch(asyncResult)).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/live/stream")
                        .header("Authorization", "Bearer " + studentToken)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }
}
