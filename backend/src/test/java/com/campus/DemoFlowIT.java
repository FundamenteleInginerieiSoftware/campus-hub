package com.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The prototype demo (AGENTS.md §7) as one test, through the real HTTP API only: register, admin
 * creates two assets of the same model, book, double-book (409), report an incident, the asset goes
 * UNDER_MAINTENANCE, and the booking moves to the second asset.
 *
 * <p>Each step is its own test, run in order. When one breaks, its message says which step, whose
 * module it is and where to look, and the steps after it are skipped instead of failing for the same
 * reason.
 *
 * <p>Not {@code @Transactional}, unlike SecurityPassIT: Calin's reassignment listener only runs after
 * the incident transaction really commits (AFTER_COMMIT), and a rolled-back test transaction never
 * commits. So the data is real and {@link #cleanUp()} deletes it at the end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // one instance for all steps, so ids and tokens carry over
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoFlowIT {

    // Different every run, so emails, names and the model never clash with seeded data or an
    // earlier run. The model matters most: the engine looks for substitutes by model.
    private static final String RUN = Long.toString(System.currentTimeMillis(), 36);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    // Calin's shared Clock (Europe/Bucharest), the one his booking rules compare against.
    @Autowired
    Clock clock;

    String student; // "Bearer <token>"
    String admin;
    long assetA;
    long assetB;
    long reservationId;
    LocalDate bookingDay;

    // Number of the last step that passed. A step only runs if the one before it passed.
    int passedStep = 0;

    @BeforeAll
    void pickBookingDay() {
        // Calin's rules: Mon-Fri 08:00-20:00, start in the future, at most 14 days ahead. The next
        // weekday after today always fits. It is computed from the app's Clock on every run, not
        // written as a date, so the test can't go stale like the one in issue #94.
        // Not a fixed Clock bean: login signs tokens with this Clock, but the token check uses the
        // real time, so tokens from a frozen date would be expired a few hours later.
        bookingDay = LocalDate.now(clock).plusDays(1);
        while (bookingDay.getDayOfWeek() == DayOfWeek.SATURDAY || bookingDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            bookingDay = bookingDay.plusDays(1);
        }
    }

    @Test
    @Order(1)
    void step1_studentRegistersAndLogsIn() throws Exception {
        String step = "Step 1 (register + login, Matei): POST /api/v1/auth/register and /login;"
                + " see AuthController/AuthService";
        call(
                step,
                201,
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"email": "student-%s@demo.test", "password": "secret123", "fullName": "Demo Student"}
                """.formatted(RUN)));
        student = login(step, "student-" + RUN + "@demo.test");
        passedStep = 1;
    }

    @Test
    @Order(2)
    void step2_adminCreatesTwoAssetsOfTheSameModel() throws Exception {
        requirePassed(1);
        // Register only makes students and the dev seeder doesn't run in tests, so the admin goes
        // straight into the table, then logs in through the API like anyone else.
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, ?, 'Demo Admin', 'ADMIN', true, NOW())
                """, "admin-" + RUN + "@demo.test", passwordEncoder.encode("secret123"));
        admin = login("Step 2 (admin login, Matei): POST /api/v1/auth/login", "admin-" + RUN + "@demo.test");

        String step = "Step 2 (catalog, Liviu): POST /api/v1/admin/categories, /lab-rooms, /assets;"
                + " see CategoryController/LabRoomController/AssetController";
        long categoryId = createdId(step, admin, "/api/v1/admin/categories", """
                {"name": "Demo scopes %s"}
                """.formatted(RUN));
        long labRoomId = createdId(step, admin, "/api/v1/admin/lab-rooms", """
                {"code": "D-%s", "name": "Demo lab"}
                """.formatted(RUN));
        assetA = createdId(step, admin, "/api/v1/admin/assets", asset("A", categoryId, labRoomId));
        assetB = createdId(step, admin, "/api/v1/admin/assets", asset("B", categoryId, labRoomId));
        passedStep = 2;
    }

    @Test
    @Order(3)
    void step3_studentBooksAssetA() throws Exception {
        requirePassed(2);
        String step = "Step 3 (booking, Calin): POST /api/v1/reservations should return 201;"
                + " see ReservationController/ReservationService.createReservation";
        String body = call(step, 201, postJson(student, "/api/v1/reservations", booking(assetA, 10)));
        reservationId = id(body);
        assertThat((String) JsonPath.read(body, "$.status"))
                .as("%s: a new booking should be CONFIRMED, body: %s", step, body)
                .isEqualTo("CONFIRMED");
        passedStep = 3;
    }

    @Test
    @Order(4)
    void step4_sameSlotAgainIsRejected() throws Exception {
        requirePassed(3);
        String step = "Step 4 (overlap check, Calin): POST /api/v1/reservations for a taken slot should return 409;"
                + " see ReservationService.createReservation (existsOverlap)";
        String body = call(step, 409, postJson(student, "/api/v1/reservations", booking(assetA, 10)));
        assertCode(step, body, "RESERVATION_CONFLICT");
        passedStep = 4;
    }

    @Test
    @Order(5)
    void step5_myReservationsShowsTheBooking() throws Exception {
        requirePassed(4);
        String step = "Step 5 (My reservations, Calin): GET /api/v1/reservations/me should list the booking;"
                + " see ReservationService.getMyReservations";
        String body = call(step, 200, get("/api/v1/reservations/me").header("Authorization", student));
        List<Integer> ids = JsonPath.read(body, "$.content[*].id");
        assertThat(ids)
                .as("%s: expected exactly reservation #%d, body: %s", step, reservationId, body)
                .containsExactly((int) reservationId);
        passedStep = 5;
    }

    @Test
    @Order(6)
    void step6_studentReportsAnIncidentOnAssetA() throws Exception {
        requirePassed(5);
        String step = "Step 6 (incident, Stefania): POST /api/v1/incidents should return 201;"
                + " see IncidentController/IncidentService.reportIncident";
        // In tests, AsyncConfig swaps the background pool for one that runs on the caller's thread,
        // so Calin's @Async AFTER_COMMIT listener has already moved the booking when this call returns.
        // No waiting needed in step 9.
        call(step, 201, postJson(student, "/api/v1/incidents", """
                {"assetId": %d, "description": "Lens cracked during the demo", "severity": "HIGH"}
                """.formatted(assetA)));
        passedStep = 6;
    }

    @Test
    @Order(7)
    void step7_assetAIsUnderMaintenance() throws Exception {
        requirePassed(6);
        String step = "Step 7 (incident -> catalog, Stefania + Liviu): GET /api/v1/assets/{id} should show"
                + " UNDER_MAINTENANCE; see IncidentService.reportIncident (markUnderMaintenance) and CatalogService";
        String body = call(step, 200, get("/api/v1/assets/{id}", assetA).header("Authorization", student));
        assertThat((String) JsonPath.read(body, "$.status"))
                .as("%s: wrong status, body: %s", step, body)
                .isEqualTo("UNDER_MAINTENANCE");
        passedStep = 7;
    }

    @Test
    @Order(8)
    void step8_bookingTheDamagedAssetIsRejected() throws Exception {
        requirePassed(7);
        String step = "Step 8 (booking a damaged asset, Calin): POST /api/v1/reservations should return 409;"
                + " see ReservationService.createReservation (isBookable)";
        // A free slot (14:00), so the only reason to refuse is the asset's status, not an overlap.
        String body = call(step, 409, postJson(student, "/api/v1/reservations", booking(assetA, 14)));
        assertCode(step, body, "ASSET_NOT_BOOKABLE");
        passedStep = 8;
    }

    @Test
    @Order(9)
    void step9_bookingMovedToAssetB() throws Exception {
        requirePassed(8);
        String step = "Step 9 (reassignment, Calin): GET /api/v1/reservations/{id} should show the booking on asset B;"
                + " see LocalAssetDamagedListener -> ReassignmentService.handle -> SameModelStrategy";
        String body =
                call(step, 200, get("/api/v1/reservations/{id}", reservationId).header("Authorization", student));
        Map<String, Object> reservation = JsonPath.read(body, "$");
        assertThat(reservation)
                .as(
                        "%s: expected assetId=%d (B), originalAssetId=%d (A), still CONFIRMED; body: %s",
                        step, assetB, assetA, body)
                .containsEntry("assetId", (int) assetB)
                .containsEntry("originalAssetId", (int) assetA)
                .containsEntry("status", "CONFIRMED");
        passedStep = 9;
    }

    @Test
    @Order(10)
    void step10_adminSeesTheMoveInTheLog() throws Exception {
        requirePassed(9);
        String step = "Step 10 (reassignment log, Calin): GET /api/v1/admin/reassignments should list the move;"
                + " see ReassignmentBookingProcessor (saves the log) and AdminReassignmentController";
        String body =
                call(step, 200, get("/api/v1/admin/reassignments?size=100").header("Authorization", admin));
        List<Map<String, Object>> entries =
                JsonPath.read(body, "$.content[?(@.reservationId == %d)]".formatted(reservationId));
        assertThat(entries)
                .as("%s: expected one entry for reservation #%d, body: %s", step, reservationId, body)
                .singleElement()
                .satisfies(entry -> assertThat(entry)
                        .as("%s: wrong log entry", step)
                        .containsEntry("outcome", "REASSIGNED")
                        .containsEntry("fromAssetId", (int) assetA)
                        .containsEntry("toAssetId", (int) assetB)
                        .containsEntry("strategy", "SameModelStrategy"));
        passedStep = 10;
    }

    // Deletes this run's rows, children before parents (foreign keys). Runs even when a step failed.
    @AfterAll
    void cleanUp() {
        String users =
                "SELECT id FROM users WHERE email IN ('student-" + RUN + "@demo.test', 'admin-" + RUN + "@demo.test')";
        String assets = "SELECT id FROM assets WHERE model = 'DEMO-" + RUN + "'";
        String reservations = "SELECT id FROM reservations WHERE user_id IN (" + users + ")";
        jdbcTemplate.update("DELETE FROM reassignment_log WHERE reservation_id IN (" + reservations + ")");
        jdbcTemplate.update("DELETE FROM incident_reports WHERE asset_id IN (" + assets + ")");
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (" + users + ")"
                + " OR (entity_type = 'Reservation' AND entity_id IN (" + reservations + "))");
        jdbcTemplate.update("DELETE FROM reservations WHERE id IN (" + reservations + ")");
        jdbcTemplate.update("DELETE FROM assets WHERE model = 'DEMO-" + RUN + "'");
        // Liviu's LabRoom stores codes in upper case, so "D-abc" is saved as "D-ABC".
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE code = 'D-" + RUN.toUpperCase(Locale.ROOT) + "'");
        jdbcTemplate.update("DELETE FROM categories WHERE name = 'Demo scopes " + RUN + "'");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (" + users + ")");
    }

    // ---- helpers ---------------------------------------------------------------------------

    // Skips (not fails) a step when an earlier one broke, so only the real culprit shows up red.
    private void requirePassed(int previousStep) {
        assumeTrue(passedStep >= previousStep, "Skipped: step " + (passedStep + 1) + " failed, fix that one first");
    }

    // Sends the request and checks the status. On a wrong status the message holds the whole body,
    // so the error "code" from the ProblemDetail tells what went wrong without a debugger.
    private String call(String step, int expectedStatus, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status)
                .as("%s: expected HTTP %d but got %d, body: %s", step, expectedStatus, status, body)
                .isEqualTo(expectedStatus);
        return body;
    }

    private void assertCode(String step, String body, String expectedCode) {
        assertThat((String) JsonPath.read(body, "$.code"))
                .as("%s: wrong error code, body: %s", step, body)
                .isEqualTo(expectedCode);
    }

    private String login(String step, String email) throws Exception {
        String body = call(
                step,
                200,
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"email": "%s", "password": "secret123"}
                """.formatted(email)));
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    private long createdId(String step, String token, String path, String json) throws Exception {
        return id(call(step + " (" + path + ")", 201, postJson(token, path, json)));
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder postJson(String token, String path, String json) {
        return post(path)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private static String asset(String letter, long categoryId, long labRoomId) {
        return """
                {"name": "Demo scope %s", "model": "DEMO-%s", "serialNumber": "SN-%s-%s",
                 "categoryId": %d, "labRoomId": %d}
                """.formatted(letter, RUN, RUN, letter, categoryId, labRoomId);
    }

    // A one-hour booking on bookingDay, starting at the given hour.
    private String booking(long assetId, int startHour) {
        LocalDateTime start = bookingDay.atTime(startHour, 0);
        return """
                {"assetId": %d, "startTime": "%s", "endTime": "%s"}
                """.formatted(assetId, start, start.plusHours(1));
    }
}
