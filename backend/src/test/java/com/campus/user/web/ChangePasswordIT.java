package com.campus.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end flow for {@code PUT /api/v1/users/me/password} against {@code campus_test}: verifies
 * real BCrypt hashing, real JWT authentication, subsequent login behavior, and the persisted
 * {@code USER_PASSWORD_CHANGED} audit row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // MockMvc runs on the test thread, so new users and audit rows roll back after each test
class ChangePasswordIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void changingPasswordInvalidatesOldLoginEnablesNewLoginAndWritesAuditRow() throws Exception {
        long userId = register("change-ok@campus.test", "oldPass123");
        String token = loginAndExtractToken("change-ok@campus.test", "oldPass123");

        MockHttpServletResponse changeResponse = changePassword(token, "oldPass123", "newPass456");

        assertThat(changeResponse.getStatus())
                .as(
                        "PUT /api/v1/users/me/password should return 204 (body: %s); see UserController.changePassword",
                        changeResponse.getContentAsString())
                .isEqualTo(204);

        MockHttpServletResponse oldLogin = attemptLogin("change-ok@campus.test", "oldPass123");
        assertThat(oldLogin.getStatus())
                .as(
                        "old password must be rejected with 401 after change (body: %s); see UserService.changePassword and AuthService.login",
                        oldLogin.getContentAsString())
                .isEqualTo(401);
        assertThat(codeOf(oldLogin))
                .as("old password login must return INVALID_CREDENTIALS; see AuthService.login")
                .isEqualTo("INVALID_CREDENTIALS");

        MockHttpServletResponse newLogin = attemptLogin("change-ok@campus.test", "newPass456");
        assertThat(newLogin.getStatus())
                .as(
                        "new password must log in with 200 (body: %s); see UserService.changePassword and AuthService.login",
                        newLogin.getContentAsString())
                .isEqualTo(200);
        String newToken = JsonPath.read(newLogin.getContentAsString(), "$.accessToken");
        assertThat(newToken)
                .as("login with new password must return a non-empty JWT; see AuthService.login")
                .isNotBlank();

        List<Map<String, Object>> auditRows = jdbcTemplate.queryForList(
                "SELECT actor_id, action, entity_type, entity_id, details FROM audit_logs "
                        + "WHERE action = 'USER_PASSWORD_CHANGED' AND entity_id = ?",
                userId);
        assertThat(auditRows)
                .as("password change must persist one USER_PASSWORD_CHANGED audit row; see UserService.changePassword")
                .hasSize(1);
        Map<String, Object> auditRow = auditRows.getFirst();
        assertThat(((Number) auditRow.get("actor_id")).longValue())
                .as("audit actor_id must be the logged-in user's id; see UserService.changePassword")
                .isEqualTo(userId);
        assertThat(auditRow.get("entity_type"))
                .as("audit entity_type must be USER; see UserService.changePassword")
                .isEqualTo("USER");
        String details = String.valueOf(auditRow.get("details"));
        assertThat(details)
                .as("audit details must never leak passwords or hashes; see UserService.changePassword")
                .doesNotContain("oldPass123")
                .doesNotContain("newPass456")
                .doesNotContain("{bcrypt}");
    }

    @Test
    void wrongCurrentPasswordReturns401LeavesOldPasswordWorkingAndWritesNoAuditRow() throws Exception {
        long userId = register("change-wrong@campus.test", "oldPass123");
        String token = loginAndExtractToken("change-wrong@campus.test", "oldPass123");

        MockHttpServletResponse response = changePassword(token, "notThePassword", "newPass456");

        assertThat(response.getStatus())
                .as(
                        "wrong current password must return 401 (body: %s); see UserService.changePassword",
                        response.getContentAsString())
                .isEqualTo(401);
        assertThat(codeOf(response))
                .as("wrong current password must return INVALID_CREDENTIALS; see UserService.changePassword")
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(attemptLogin("change-wrong@campus.test", "oldPass123").getStatus())
                .as("failed change must keep the old password working; see UserService.changePassword")
                .isEqualTo(200);
        assertThat(passwordChangedAuditCount(userId))
                .as("failed password change must not write an audit row; see UserService.changePassword")
                .isZero();
    }

    @Test
    void reusingCurrentPasswordReturns409AndWritesNoAuditRow() throws Exception {
        long userId = register("change-same@campus.test", "samePass123");
        String token = loginAndExtractToken("change-same@campus.test", "samePass123");

        MockHttpServletResponse response = changePassword(token, "samePass123", "samePass123");

        assertThat(response.getStatus())
                .as(
                        "reusing the current password must return 409 (body: %s); see UserService.changePassword",
                        response.getContentAsString())
                .isEqualTo(409);
        assertThat(codeOf(response))
                .as("reusing the current password must return PASSWORD_UNCHANGED; see UserService.changePassword")
                .isEqualTo("PASSWORD_UNCHANGED");
        assertThat(passwordChangedAuditCount(userId))
                .as("rejected reuse must not write an audit row; see UserService.changePassword")
                .isZero();
    }

    @Test
    void currentPasswordOver72Utf8BytesReturns401InvalidCredentialsWithoutServerError() throws Exception {
        long userId = register("change-long@campus.test", "oldPass123");
        String token = loginAndExtractToken("change-long@campus.test", "oldPass123");

        MockHttpServletResponse response = changePassword(token, "ș".repeat(37), "newPass456");

        assertThat(response.getStatus())
                .as(
                        "currentPassword over 72 UTF-8 bytes must return 401, never 400 or 500 (body: %s); see UserService.changePassword",
                        response.getContentAsString())
                .isEqualTo(401);
        assertThat(codeOf(response))
                .as("over-72-byte currentPassword must return INVALID_CREDENTIALS; see UserService.changePassword")
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(passwordChangedAuditCount(userId))
                .as("rejected >72-byte currentPassword must not write an audit row; see UserService.changePassword")
                .isZero();
    }

    private long register(String email, String password) throws Exception {
        String body = jsonMapper.writeValueAsString(new RegisterRequest(email, password, "Change Test"));
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus())
                .as("setup register failed (body: %s)", response.getContentAsString())
                .isEqualTo(201);
        return ((Number) JsonPath.read(response.getContentAsString(), "$.id")).longValue();
    }

    private MockHttpServletResponse attemptLogin(String email, String password) throws Exception {
        String body = jsonMapper.writeValueAsString(new LoginRequest(email, password));
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private String loginAndExtractToken(String email, String password) throws Exception {
        MockHttpServletResponse response = attemptLogin(email, password);
        assertThat(response.getStatus())
                .as("setup login failed (body: %s)", response.getContentAsString())
                .isEqualTo(200);
        return JsonPath.read(response.getContentAsString(), "$.accessToken");
    }

    private MockHttpServletResponse changePassword(String token, String currentPassword, String newPassword)
            throws Exception {
        String body = jsonMapper.writeValueAsString(new ChangePasswordRequest(currentPassword, newPassword));
        return mockMvc.perform(put("/api/v1/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private int passwordChangedAuditCount(long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'USER_PASSWORD_CHANGED' AND entity_id = ?",
                Integer.class,
                userId);
        return count == null ? 0 : count;
    }

    private String codeOf(MockHttpServletResponse response) throws Exception {
        return jsonMapper.readTree(response.getContentAsString()).path("code").asString();
    }
}
