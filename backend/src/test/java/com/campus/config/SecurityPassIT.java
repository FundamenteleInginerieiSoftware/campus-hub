package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The W10 security pass, as tests: the whole app, real users, real tokens from login.
 *
 * <ol>
 *   <li>A student can't read or cancel another student's reservation: 404, as if it didn't exist
 *       (AGENTS.md §5 "Others' resources return 404").
 *   <li>Every endpoint under /api/v1/admin/** is closed to students (403) and to callers without a
 *       token (401). The list is read from Spring at runtime, so a new admin endpoint is checked as
 *       soon as a teammate adds it.
 *   <li>Error bodies are ProblemDetail JSON with a "code" and never show stack traces, class names
 *       or SQL.
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityPassIT.FailingController.class)
@Transactional // MockMvc runs on the test thread, so all users, assets and bookings are rolled back
class SecurityPassIT {

    // Words that only show up in an error body when internals leak (stack trace, exception class,
    // SQL from Hibernate or the JDBC driver). Compared in lower case.
    private static final List<String> LEAK_MARKERS = List.of(
            "trace",
            "exception",
            "com.campus",
            "org.springframework",
            "java.",
            "hibernate",
            "jdbc",
            "sql",
            "insert into",
            "select ",
            "constraint");

    private static final long MISSING_ID = 999_999_999L;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    // Calin's shared Clock (zone Europe/Bucharest): his booking rules compare against it.
    @Autowired
    Clock clock;

    // Spring's list of every @RequestMapping in the app. The qualifier picks the one that holds our
    // controllers, not actuator's.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // "Bearer <token>" header values, from a real login.
    String studentA;
    String studentB;
    long assetId;
    long reservationOfA;
    LocalDate bookingDay;

    @BeforeEach
    void setUp() throws Exception {
        // Register only makes students, so the admin goes straight into the table and then logs in
        // like anyone else.
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES ('admin@secpass.test', ?, 'Pass Admin', 'ADMIN', true, NOW())
                """, passwordEncoder.encode("secret123"));
        String admin = login("admin@secpass.test");
        studentA = registerAndLogin("a@secpass.test");
        studentB = registerAndLogin("b@secpass.test");

        // One asset through Liviu's admin endpoints, the same way the admin UI creates it.
        long categoryId = createdId(admin, "/api/v1/admin/categories", "{\"name\": \"Pass scopes\"}");
        long labRoomId = createdId(admin, "/api/v1/admin/lab-rooms", "{\"code\": \"PASS-1\", \"name\": \"Pass lab\"}");
        assetId = createdId(admin, "/api/v1/admin/assets", """
                {"name": "Pass scope", "model": "PASS-MODEL", "serialNumber": "SN-PASS-1",
                 "categoryId": %d, "labRoomId": %d}
                """.formatted(categoryId, labRoomId));

        // Calin's rules: Mon-Fri 08:00-20:00, at most 14 days ahead. The next weekday always fits.
        // No fixed Clock here: login tokens expire by the real time, so tokens signed at a fixed
        // date would make this test fail a few hours after that date.
        bookingDay = LocalDateTime.now(clock).toLocalDate().plusDays(1);
        while (bookingDay.getDayOfWeek() == DayOfWeek.SATURDAY || bookingDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            bookingDay = bookingDay.plusDays(1);
        }
        reservationOfA = createdId(studentA, "/api/v1/reservations", booking(10, ""));
    }

    // ---- 1. IDOR: another student's reservation looks like a missing one -------------------

    @Test
    void otherStudentCannotReadMyReservation() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationOfA).header("Authorization", studentA))
                .andExpect(status().isOk());

        assertCleanProblem(getAs(studentB, "/api/v1/reservations/" + reservationOfA), 404, "RESERVATION_NOT_FOUND");
        // Same answer as for an id that doesn't exist, so B can't even learn which ids are taken.
        assertCleanProblem(getAs(studentB, "/api/v1/reservations/" + MISSING_ID), 404, "RESERVATION_NOT_FOUND");
    }

    @Test
    void otherStudentCannotCancelMyReservation() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/reservations/{id}/cancel", reservationOfA).header("Authorization", studentB))
                .andReturn();

        assertCleanProblem(result, 404, "RESERVATION_NOT_FOUND");
        mockMvc.perform(get("/api/v1/reservations/{id}", reservationOfA).header("Authorization", studentA))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void myListsOnlyShowMyOwnReservations() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/me").header("Authorization", studentB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/reservations/me/summary").header("Authorization", studentB))
                .andExpect(jsonPath("$.activeCount").value(0));

        mockMvc.perform(get("/api/v1/reservations/me").header("Authorization", studentA))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(reservationOfA));
    }

    // The owner always comes from the token (AGENTS.md §5). A student who puts someone else's id
    // in the body still books and reports as themselves.
    @Test
    void userIdInTheBodyIsIgnored() throws Exception {
        long idOfA = userId(studentA);
        long idOfB = userId(studentB);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Authorization", studentB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(booking(14, ", \"userId\": " + idOfA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(idOfB));

        String incident = """
                {"assetId": %d, "description": "Screen cracked", "severity": "HIGH", "reportedById": %d}
                """.formatted(assetId, idOfA);
        mockMvc.perform(post("/api/v1/incidents")
                        .header("Authorization", studentB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incident))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportedById").value(idOfB));
    }

    // ---- 2. Every admin endpoint is closed to students and to callers without a token -------

    @Test
    void everyAdminEndpointIsClosedToStudentsAndAnonymousCallers() throws Exception {
        List<String> adminEndpoints = adminEndpoints();
        // Guards against a test that passes because it found nothing to check.
        assertThat(adminEndpoints).contains("POST /api/v1/admin/assets", "GET /api/v1/admin/reservations");

        // Soft assertions: one run lists every endpoint that is open, not only the first one.
        SoftAssertions softly = new SoftAssertions();
        for (String endpoint : adminEndpoints) {
            HttpMethod method = HttpMethod.valueOf(endpoint.split(" ")[0]);
            String path = endpoint.split(" ")[1];

            MvcResult asStudent = mockMvc.perform(request(method, path).header("Authorization", studentB))
                    .andReturn();
            MvcResult noToken = mockMvc.perform(request(method, path)).andReturn();

            softly.assertThat(statusAndCode(asStudent))
                    .as("student on " + endpoint)
                    .isEqualTo("403 FORBIDDEN");
            softly.assertThat(statusAndCode(noToken))
                    .as("no token on " + endpoint)
                    .isEqualTo("401 UNAUTHORIZED");
        }
        softly.assertAll();
    }

    // ---- 3. Error bodies never show internals ----------------------------------------------

    @Test
    void errorBodiesAreCleanProblemDetails() throws Exception {
        assertCleanProblem(getAs(studentA, "/api/v1/does-not-exist"), 404, "NOT_FOUND");
        assertCleanProblem(getAs(studentA, "/api/v1/assets/" + MISSING_ID), 404, "ASSET_NOT_FOUND");
        assertCleanProblem(getAs(studentA, "/api/v1/reservations/abc"), 400, "INVALID_PARAMETER");
        assertCleanProblem(postJson(studentA, "/api/v1/reservations", "{not json"), 400, "MALFORMED_REQUEST");
        assertCleanProblem(postJson(studentA, "/api/v1/reservations", "{}"), 400, "VALIDATION_FAILED");
        // Student B asks for A's slot.
        assertCleanProblem(postJson(studentB, "/api/v1/reservations", booking(10, "")), 409, "RESERVATION_CONFLICT");
        assertCleanProblem(
                mockMvc.perform(request(HttpMethod.DELETE, "/api/v1/reservations/" + reservationOfA)
                                .header("Authorization", studentA))
                        .andReturn(),
                405,
                "METHOD_NOT_ALLOWED");
        assertCleanProblem(mockMvc.perform(get("/api/v1/users/me")).andReturn(), 401, "UNAUTHORIZED");
        assertCleanProblem(getAs(studentA, "/api/v1/admin/reservations"), 403, "FORBIDDEN");
    }

    // A database error nobody caught is the usual way SQL ends up in a response. Liviu's
    // GlobalExceptionHandler must only log it and answer with a generic 500.
    @Test
    void unexpectedErrorHidesTheDatabaseMessage() throws Exception {
        MvcResult result = getAs(studentA, FailingController.PATH);

        assertCleanProblem(result, 500, "INTERNAL_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("users_email_key", "secpass");
    }

    // Test-only endpoint that fails like a real unique-key violation: no real endpoint fails on purpose.
    @RestController
    static class FailingController {

        static final String PATH = "/api/v1/test-security-pass/fail";

        @GetMapping(PATH)
        String fail() {
            throw new DataIntegrityViolationException(
                    "could not execute statement [ERROR: duplicate key value violates unique constraint"
                            + " \"users_email_key\"] [insert into users (email) values ('a@secpass.test')]");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    // "GET /api/v1/admin/reservations", "POST /api/v1/admin/assets/1/retire", ... Path variables get
    // the value 1: security only looks at the path prefix, so any value gives the same answer.
    private List<String> adminEndpoints() {
        List<String> endpoints = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith("/api/v1/admin/")) {
                    String path = pattern.replaceAll("\\{[^}]+}", "1");
                    // A mapping without a method accepts them all; GET stands for them.
                    if (methods.isEmpty()) {
                        endpoints.add("GET " + path);
                    }
                    methods.forEach(method -> endpoints.add(method.name() + " " + path));
                }
            }
        }
        return endpoints;
    }

    private static void assertCleanProblem(MvcResult result, int expectedStatus, String expectedCode) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(statusAndCode(result)).as(body).isEqualTo(expectedStatus + " " + expectedCode);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body.toLowerCase()).as(body).doesNotContain(LEAK_MARKERS);
    }

    // "403 FORBIDDEN". A body that isn't one of our errors (e.g. a 200 list) shows as "200 (no code)",
    // so the soft assertion reports it instead of crashing the loop.
    private static String statusAndCode(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String code;
        try {
            code = JsonPath.read(body, "$.code");
        } catch (RuntimeException notAnErrorBody) {
            code = "(no code)";
        }
        return result.getResponse().getStatus() + " " + code;
    }

    private MvcResult getAs(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", token)).andReturn();
    }

    private MvcResult postJson(String token, String path, String json) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private long createdId(String token, String path, String json) throws Exception {
        MvcResult result = postJson(token, path, json);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(path + ": " + body).isEqualTo(201);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long userId(String token) throws Exception {
        String body = getAs(token, "/api/v1/users/me").getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    // A one-hour booking of the test asset on bookingDay, starting at the given hour.
    private String booking(int startHour, String extraJson) {
        LocalDateTime start = bookingDay.atTime(startHour, 0);
        return "{\"assetId\": %d, \"startTime\": \"%s\", \"endTime\": \"%s\"%s}"
                .formatted(assetId, start, start.plusHours(1), extraJson);
    }

    private String registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"secret123\", \"fullName\": \"Pass\"}"))
                .andExpect(status().isCreated());
        return login(email);
    }

    private String login(String email) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return "Bearer " + JsonPath.read(json, "$.accessToken");
    }
}
