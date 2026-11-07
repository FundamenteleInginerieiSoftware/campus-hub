package com.campus.user.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * User admin end to end: the whole app, the real database and real tokens from login. The
 * interesting part is what happens to a token that already exists: SecurityConfig reads the role and
 * the enabled flag from the users row on every request (#117), so a disable or a role change counts
 * from the very next request, without logging in again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // MockMvc runs on the test thread, so every user and audit row is rolled back
class AdminUserFlowIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    // "Bearer <token>" header values, from a real login.
    String admin;
    String otherAdmin;
    String student;
    long adminId;
    long otherAdminId;
    long studentId;

    @BeforeEach
    void setUp() throws Exception {
        // Register only makes students, so the admins go straight into the table and then log in.
        insertAdmin("admin@uadmflow.test");
        insertAdmin("admin2@uadmflow.test");
        admin = login("admin@uadmflow.test");
        otherAdmin = login("admin2@uadmflow.test");
        student = registerAndLogin("maria@uadmflow.test", "Maria Ionescu");

        adminId = userId(admin);
        otherAdminId = userId(otherAdmin);
        studentId = userId(student);
    }

    // ---- search and details ------------------------------------------------------------------

    @Test
    void searchFindsByPartOfTheNameOrEmail_andNeverShowsThePasswordHash() throws Exception {
        MvcResult byName = getAs(admin, "/api/v1/admin/users?q=ionescu&size=100");
        MvcResult byEmail = getAs(admin, "/api/v1/admin/users?q=MARIA@UADM&size=100");

        assertThat(byName.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(byName)).containsExactly(studentId);
        assertThat(ids(byEmail)).containsExactly(studentId);
        assertThat(body(byName)).doesNotContain("passwordHash").doesNotContain("password_hash");
    }

    @Test
    void searchFiltersByRoleAndEnabled() throws Exception {
        MvcResult admins = getAs(admin, "/api/v1/admin/users?q=uadmflow&role=ADMIN&size=100");
        MvcResult students = getAs(admin, "/api/v1/admin/users?q=uadmflow&role=STUDENT&size=100");

        assertThat(ids(admins)).containsExactlyInAnyOrder(adminId, otherAdminId);
        assertThat(ids(students)).containsExactly(studentId);
    }

    // "%" must match a real percent sign, not "anything".
    @Test
    void searchTreatsPercentLiterally() throws Exception {
        MvcResult result = getAs(admin, "/api/v1/admin/users?q=uadm%25flow&size=100");

        assertThat(ids(result)).isEmpty();
    }

    @Test
    void detailsShowEnabledAndCreatedAt() throws Exception {
        MvcResult result = getAs(admin, "/api/v1/admin/users/" + studentId);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(result, "$.email")).isEqualTo("maria@uadmflow.test");
        assertThat(field(result, "$.role")).isEqualTo("STUDENT");
        assertThat((Boolean) JsonPath.read(body(result), "$.enabled")).isTrue();
        assertThat(field(result, "$.createdAt")).isNotBlank();
    }

    @Test
    void anUnknownUser_isA404() throws Exception {
        MvcResult result = getAs(admin, "/api/v1/admin/users/999999999");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(result, "$.code")).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void aStudentCannotUseTheAdminEndpoints() throws Exception {
        assertThat(getAs(student, "/api/v1/admin/users").getResponse().getStatus())
                .isEqualTo(403);
        // and certainly cannot promote themselves
        MvcResult selfPromote = putJson(student, "/api/v1/admin/users/" + studentId + "/role", "{\"role\":\"ADMIN\"}");
        assertThat(selfPromote.getResponse().getStatus()).isEqualTo(403);
        assertThat(field(getAs(admin, "/api/v1/admin/users/" + studentId), "$.role"))
                .isEqualTo("STUDENT");
    }

    // ---- disable and enable ------------------------------------------------------------------

    @Test
    void disablingAStudent_stopsTheirTokenAtOnce_andTheirLogin() throws Exception {
        assertThat(getAs(student, "/api/v1/users/me").getResponse().getStatus()).isEqualTo(200);

        MvcResult disabled = postAs(admin, "/api/v1/admin/users/" + studentId + "/disable");

        assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(disabled), "$.enabled")).isFalse();
        // The token is still signed and not expired, but the account is off: 401 on the next request.
        assertThat(getAs(student, "/api/v1/users/me").getResponse().getStatus()).isEqualTo(401);
        assertThat(loginStatus("maria@uadmflow.test")).isEqualTo(401);
        assertThat(auditCount("USER_DISABLED", studentId)).isEqualTo(1);
    }

    @Test
    void enablingAgain_letsThemLogIn() throws Exception {
        postAs(admin, "/api/v1/admin/users/" + studentId + "/disable");

        MvcResult enabled = postAs(admin, "/api/v1/admin/users/" + studentId + "/enable");

        assertThat(enabled.getResponse().getStatus()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(enabled), "$.enabled")).isTrue();
        assertThat(loginStatus("maria@uadmflow.test")).isEqualTo(200);
        assertThat(auditCount("USER_ENABLED", studentId)).isEqualTo(1);
    }

    @Test
    void disablingTwice_isHarmless_andIsAuditedOnce() throws Exception {
        postAs(admin, "/api/v1/admin/users/" + studentId + "/disable");
        MvcResult again = postAs(admin, "/api/v1/admin/users/" + studentId + "/disable");

        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(auditCount("USER_DISABLED", studentId)).isEqualTo(1);
    }

    @Test
    void anAdminCannotDisableThemselves() throws Exception {
        MvcResult result = postAs(admin, "/api/v1/admin/users/" + adminId + "/disable");

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(result, "$.code")).isEqualTo("CANNOT_MODIFY_SELF");
        assertThat(getAs(admin, "/api/v1/users/me").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- roles -------------------------------------------------------------------------------

    @Test
    void promotingAStudent_givesTheirExistingTokenAdminAccess_andDemotingTakesItAway() throws Exception {
        assertThat(getAs(student, "/api/v1/admin/users").getResponse().getStatus())
                .isEqualTo(403);

        MvcResult promoted = putJson(admin, "/api/v1/admin/users/" + studentId + "/role", "{\"role\":\"ADMIN\"}");
        assertThat(promoted.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(promoted, "$.role")).isEqualTo("ADMIN");
        assertThat(getAs(student, "/api/v1/admin/users").getResponse().getStatus())
                .isEqualTo(200);

        putJson(admin, "/api/v1/admin/users/" + studentId + "/role", "{\"role\":\"STUDENT\"}");
        assertThat(getAs(student, "/api/v1/admin/users").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(auditCount("USER_ROLE_CHANGED", studentId)).isEqualTo(2);
    }

    @Test
    void anAdminCanDemoteAnotherAdmin_butNotThemselves() throws Exception {
        MvcResult other = putJson(admin, "/api/v1/admin/users/" + otherAdminId + "/role", "{\"role\":\"STUDENT\"}");
        MvcResult self = putJson(admin, "/api/v1/admin/users/" + adminId + "/role", "{\"role\":\"STUDENT\"}");

        assertThat(other.getResponse().getStatus()).isEqualTo(200);
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(self, "$.code")).isEqualTo("CANNOT_MODIFY_SELF");
        assertThat(getAs(otherAdmin, "/api/v1/admin/users").getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void anUnknownRole_isA400() throws Exception {
        MvcResult result = putJson(admin, "/api/v1/admin/users/" + studentId + "/role", "{\"role\":\"TEACHER\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(result, "$.code")).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void aHugePage_isA400_notA500() throws Exception {
        MvcResult result = getAs(admin, "/api/v1/admin/users?page=999999999&size=100");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(result, "$.code")).isEqualTo("INVALID_PARAMETER");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void insertAdmin(String email) {
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, ?, 'Flow Admin', 'ADMIN', true, NOW())
                """, email, passwordEncoder.encode("secret123"));
    }

    private int auditCount(String action, long entityId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
        return count == null ? 0 : count;
    }

    private static List<Long> ids(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(body(result), "$.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String field(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }

    private MvcResult getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", token)).andReturn();
    }

    private MvcResult postAs(String token, String path) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", token)).andReturn();
    }

    private MvcResult putJson(String token, String path, String json) throws Exception {
        return mockMvc.perform(put(path)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult postJson(String path, String json) throws Exception {
        return mockMvc.perform(
                        post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private long userId(String token) throws Exception {
        return ((Number) JsonPath.read(body(getAs(token, "/api/v1/users/me")), "$.id")).longValue();
    }

    private String registerAndLogin(String email, String fullName) throws Exception {
        MvcResult result = postJson(
                "/api/v1/auth/register",
                "{\"email\": \"" + email + "\", \"password\": \"secret123\", \"fullName\": \"" + fullName + "\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return login(email);
    }

    private int loginStatus(String email) throws Exception {
        return postJson("/api/v1/auth/login", "{\"email\": \"" + email + "\", \"password\": \"secret123\"}")
                .getResponse()
                .getStatus();
    }

    private String login(String email) throws Exception {
        MvcResult result =
                postJson("/api/v1/auth/login", "{\"email\": \"" + email + "\", \"password\": \"secret123\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return "Bearer " + JsonPath.read(body(result), "$.accessToken");
    }
}
