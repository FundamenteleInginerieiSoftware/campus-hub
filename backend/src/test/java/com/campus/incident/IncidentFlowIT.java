package com.campus.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The incident module end to end: the whole app, the real database, real tokens from login, and
 * Liviu's catalog behind CatalogApi. Each test checks what the student and the admin see AND what
 * happens to the asset, because "the asset is out of use while an incident is open" is the rule the
 * reassignment engine depends on.
 *
 * <p>Calin's reassignment listener runs only after a commit; this test rolls back, so it never runs here
 * (it has its own tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // MockMvc runs on the test thread, so all users, assets and incidents are rolled back
class IncidentFlowIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PasswordEncoder passwordEncoder;

    // "Bearer <token>" header values, from a real login.
    String admin;
    String studentA;
    String studentB;
    long assetId;

    @BeforeEach
    void setUp() throws Exception {
        // Register only makes students, so the admin goes straight into the table and then logs in.
        jdbcTemplate.update("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES ('admin@incflow.test', ?, 'Flow Admin', 'ADMIN', true, NOW())
                """, passwordEncoder.encode("secret123"));
        admin = login("admin@incflow.test");
        studentA = registerAndLogin("a@incflow.test");
        studentB = registerAndLogin("b@incflow.test");

        long categoryId = createdId(admin, "/api/v1/admin/categories", "{\"name\": \"Flow scopes\"}");
        long labRoomId = createdId(admin, "/api/v1/admin/lab-rooms", "{\"code\": \"FLOW-1\", \"name\": \"Flow lab\"}");
        assetId = createdId(admin, "/api/v1/admin/assets", """
                {"name": "Flow scope", "model": "FLOW-MODEL", "serialNumber": "SN-FLOW-1",
                 "categoryId": %d, "labRoomId": %d}
                """.formatted(categoryId, labRoomId));
    }

    // ---- the main flow -----------------------------------------------------------------------

    @Test
    void reportStartRepairResolve_takesTheAssetOutOfUseAndBringsItBack() throws Exception {
        assertThat(assetStatus()).isEqualTo("AVAILABLE");

        long incidentId = report(studentA, "Screen cracked");
        assertThat(assetStatus()).isEqualTo("UNDER_MAINTENANCE");
        assertThat(incidentStatus(studentA, incidentId)).isEqualTo("OPEN");

        MvcResult started = postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/start-repair", null);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(started, "$.status")).isEqualTo("IN_REPAIR");
        assertThat(assetStatus()).isEqualTo("UNDER_MAINTENANCE");

        MvcResult resolved = postAs(
                admin, "/api/v1/admin/incidents/" + incidentId + "/resolve", "{\"resolutionNotes\":\"New panel\"}");
        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(resolved, "$.status")).isEqualTo("RESOLVED");
        assertThat(field(resolved, "$.resolutionNotes")).isEqualTo("New panel");
        assertThat(((Number) JsonPath.read(body(resolved), "$.resolvedById")).longValue())
                .isEqualTo(userId(admin));
        assertThat(assetStatus()).isEqualTo("AVAILABLE");
    }

    // #159: every step lands in Liviu's audit log, with who did it; repeating a step adds nothing.
    @Test
    void everyStepIsAudited_once_withWhoDidIt() throws Exception {
        long incidentId = report(studentA, "Screen cracked");
        postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/start-repair", null);
        postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/resolve", "{\"resolutionNotes\":\"New panel\"}");
        postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/resolve", null); // twice: no new entry

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action, actor_id, details FROM audit_logs WHERE entity_type = 'INCIDENT' AND entity_id = ?"
                        + " ORDER BY id",
                incidentId);

        assertThat(rows)
                .extracting(row -> row.get("action"))
                .containsExactly("INCIDENT_REPORTED", "INCIDENT_REPAIR_STARTED", "INCIDENT_RESOLVED");
        assertThat(((Number) rows.get(0).get("actor_id")).longValue()).isEqualTo(userId(studentA));
        assertThat(((Number) rows.get(2).get("actor_id")).longValue()).isEqualTo(userId(admin));
        assertThat(rows.get(2).get("details")).isEqualTo("Asset " + assetId + ": New panel");
    }

    @Test
    void reject_bringsTheAssetBack() throws Exception {
        long incidentId = report(studentA, "Does not turn on");

        MvcResult rejected = postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/reject", null);

        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(field(rejected, "$.status")).isEqualTo("REJECTED");
        assertThat(assetStatus()).isEqualTo("AVAILABLE");
    }

    // Two reports on one asset: it is usable again only when BOTH are closed.
    @Test
    void theAssetStaysOutOfUse_untilTheLastIncidentOnItIsClosed() throws Exception {
        long first = report(studentA, "Screen cracked");
        long second = report(studentB, "Probe missing");

        postAs(admin, "/api/v1/admin/incidents/" + first + "/resolve", null);
        assertThat(assetStatus()).isEqualTo("UNDER_MAINTENANCE");

        postAs(admin, "/api/v1/admin/incidents/" + second + "/reject", null);
        assertThat(assetStatus()).isEqualTo("AVAILABLE");
    }

    // #146: the admin retires the asset while its incident is open. Closing the incident must still work.
    @Test
    void anIncidentOnARetiredAssetCanStillBeResolved_andTheAssetStaysRetired() throws Exception {
        long incidentId = report(studentA, "Burnt out");
        MvcResult retired = postAs(admin, "/api/v1/admin/assets/" + assetId + "/retire", null);
        assertThat(retired.getResponse().getStatus()).as(body(retired)).isEqualTo(200);

        MvcResult resolved = postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/resolve", null);

        assertThat(resolved.getResponse().getStatus()).as(body(resolved)).isEqualTo(200);
        assertThat(field(resolved, "$.status")).isEqualTo("RESOLVED");
        assertThat(assetStatus()).isEqualTo("RETIRED");
    }

    // #164: a report on a retired asset is refused and leaves nothing behind.
    @Test
    void reportingARetiredAsset_isA409_andSavesNothing() throws Exception {
        postAs(admin, "/api/v1/admin/assets/" + assetId + "/retire", null);
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incident_reports", Integer.class);

        MvcResult result = postAs(studentA, "/api/v1/incidents", reportJson(assetId, "Broken"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(result, "$.code")).isEqualTo("ASSET_RETIRED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM incident_reports", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void reportingAMissingAsset_isA404() throws Exception {
        MvcResult result = postAs(studentA, "/api/v1/incidents", reportJson(999_999_999L, "Broken"));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(result, "$.code")).isEqualTo("ASSET_NOT_FOUND");
    }

    // ---- who sees what -----------------------------------------------------------------------

    @Test
    void myIncidents_showsOnlyMine_newestFirst() throws Exception {
        long older = report(studentA, "First");
        long newer = report(studentA, "Second");
        report(studentB, "Not mine");

        MvcResult mine = getAs(studentA, "/api/v1/incidents/me");

        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        List<Number> ids = JsonPath.read(body(mine), "$.content[*].id");
        // Same second for both, so the id decides: the newer one comes first.
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactly(newer, older);
    }

    @Test
    void anotherStudentGetsA404_forMyIncident_likeForAMissingOne() throws Exception {
        long incidentId = report(studentA, "Screen cracked");

        assertThat(getAs(studentA, "/api/v1/incidents/" + incidentId)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(getAs(admin, "/api/v1/incidents/" + incidentId).getResponse().getStatus())
                .isEqualTo(200);

        MvcResult other = getAs(studentB, "/api/v1/incidents/" + incidentId);
        MvcResult missing = getAs(studentB, "/api/v1/incidents/999999999");
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(other, "$.code")).isEqualTo("INCIDENT_NOT_FOUND");
        assertThat(field(missing, "$.code")).isEqualTo("INCIDENT_NOT_FOUND");
    }

    @Test
    void aStudentCannotTriage() throws Exception {
        long incidentId = report(studentA, "Screen cracked");

        assertThat(postAs(studentA, "/api/v1/admin/incidents/" + incidentId + "/resolve", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(assetStatus()).isEqualTo("UNDER_MAINTENANCE");
    }

    @Test
    void adminQueue_filtersByStatus() throws Exception {
        long open = report(studentA, "Still open");
        long closed = report(studentB, "Will be resolved");
        postAs(admin, "/api/v1/admin/incidents/" + closed + "/resolve", null);

        MvcResult result = getAs(admin, "/api/v1/admin/incidents?status=OPEN&size=100");

        List<Number> ids = JsonPath.read(body(result), "$.content[*].id");
        assertThat(ids.stream().map(Number::longValue).toList()).contains(open).doesNotContain(closed);
    }

    // #146, #164: a huge page used to be a 500 from Hibernate.
    @Test
    void aHugePage_isA400_notA500() throws Exception {
        MvcResult mine = getAs(studentA, "/api/v1/incidents/me?page=999999999&size=100");
        MvcResult queue = getAs(admin, "/api/v1/admin/incidents?page=999999999&size=100");

        assertThat(mine.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(mine, "$.code")).isEqualTo("INVALID_PARAMETER");
        assertThat(queue.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(queue, "$.code")).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void closedIncidentsCannotBeReopened() throws Exception {
        long incidentId = report(studentA, "Screen cracked");
        postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/reject", null);

        MvcResult result = postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/start-repair", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(result, "$.code")).isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
    }

    // ---- photo (#171) ------------------------------------------------------------------------

    // Real PNG and JPEG signatures: StorageService checks the first bytes, not just the Content-Type.
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F'};

    @Test
    void theReporterAddsAPhoto_andOnlyTheReporterAndAdminsCanSeeIt() throws Exception {
        long incidentId = report(studentA, "Screen cracked");

        MvcResult uploaded = uploadAs(studentA, incidentId, "image/png", PNG);
        assertThat(uploaded.getResponse().getStatus()).as(body(uploaded)).isEqualTo(200);
        String key = field(uploaded, "$.photoKey");
        assertThat(key).endsWith(".png");
        assertThat(field(getAs(studentA, "/api/v1/incidents/" + incidentId), "$.photoKey"))
                .isEqualTo(key);

        MvcResult mine = getAs(studentA, "/api/v1/incidents/" + incidentId + "/photo");
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(mine.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(mine.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(mine.getResponse().getContentAsByteArray()).isEqualTo(PNG);

        assertThat(getAs(admin, "/api/v1/incidents/" + incidentId + "/photo")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult other = getAs(studentB, "/api/v1/incidents/" + incidentId + "/photo");
        assertThat(other.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(other, "$.code")).isEqualTo("INCIDENT_NOT_FOUND");
        MvcResult otherUpload = uploadAs(studentB, incidentId, "image/png", PNG);
        assertThat(otherUpload.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void anAdminReplacesThePhoto_andTheNewOneIsServed() throws Exception {
        long incidentId = report(studentA, "Screen cracked");
        String firstKey = field(uploadAs(studentA, incidentId, "image/png", PNG), "$.photoKey");

        MvcResult replaced = uploadAs(admin, incidentId, "image/jpeg", JPEG);

        assertThat(replaced.getResponse().getStatus()).as(body(replaced)).isEqualTo(200);
        assertThat(field(replaced, "$.photoKey")).isNotEqualTo(firstKey).endsWith(".jpg");
        MvcResult served = getAs(studentA, "/api/v1/incidents/" + incidentId + "/photo");
        assertThat(served.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT action FROM audit_logs WHERE entity_type = 'INCIDENT' AND entity_id = ? ORDER BY id",
                        String.class,
                        incidentId))
                .containsExactly("INCIDENT_REPORTED", "INCIDENT_PHOTO_ADDED", "INCIDENT_PHOTO_REPLACED");
    }

    // An HTML file renamed to .png with a PNG Content-Type: the first bytes give it away.
    @Test
    void aFileThatIsNotReallyAnImage_isA400_andNothingIsSaved() throws Exception {
        long incidentId = report(studentA, "Screen cracked");

        MvcResult result = uploadAs(studentA, incidentId, "image/png", "<html><script>".getBytes());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(field(result, "$.code")).isEqualTo("INVALID_PHOTO_TYPE");
        MvcResult photo = getAs(studentA, "/api/v1/incidents/" + incidentId + "/photo");
        assertThat(photo.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(photo, "$.code")).isEqualTo("PHOTO_NOT_FOUND");
    }

    @Test
    void aClosedIncident_keepsItsPhoto_butItCanNoLongerChange() throws Exception {
        long incidentId = report(studentA, "Screen cracked");
        uploadAs(studentA, incidentId, "image/png", PNG);
        postAs(admin, "/api/v1/admin/incidents/" + incidentId + "/resolve", null);

        MvcResult result = uploadAs(studentA, incidentId, "image/jpeg", JPEG);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(field(result, "$.code")).isEqualTo("INVALID_INCIDENT_STATUS_TRANSITION");
        assertThat(getAs(studentA, "/api/v1/incidents/" + incidentId + "/photo")
                        .getResponse()
                        .getContentAsByteArray())
                .isEqualTo(PNG);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private MvcResult uploadAs(String token, long incidentId, String contentType, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo", contentType, bytes);
        return mockMvc.perform(multipart("/api/v1/incidents/" + incidentId + "/photo")
                        .file(file)
                        .header("Authorization", token))
                .andReturn();
    }

    private long report(String token, String description) throws Exception {
        return createdId(token, "/api/v1/incidents", reportJson(assetId, description));
    }

    private static String reportJson(long asset, String description) {
        return "{\"assetId\": %d, \"description\": \"%s\", \"severity\": \"HIGH\"}".formatted(asset, description);
    }

    private String assetStatus() throws Exception {
        return field(getAs(admin, "/api/v1/assets/" + assetId), "$.status");
    }

    private String incidentStatus(String token, long incidentId) throws Exception {
        return field(getAs(token, "/api/v1/incidents/" + incidentId), "$.status");
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

    // token == null: no login (register, login). json == null: a POST without a body (start-repair,
    // reject or resolve without notes, retire).
    private MvcResult postAs(String token, String path, String json) throws Exception {
        var request = post(path);
        if (token != null) {
            request = request.header("Authorization", token);
        }
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn();
    }

    private long createdId(String token, String path, String json) throws Exception {
        MvcResult result = postAs(token, path, json);
        assertThat(result.getResponse().getStatus())
                .as(path + ": " + body(result))
                .isEqualTo(201);
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private long userId(String token) throws Exception {
        return ((Number) JsonPath.read(body(getAs(token, "/api/v1/users/me")), "$.id")).longValue();
    }

    private String registerAndLogin(String email) throws Exception {
        MvcResult result = postAs(
                null,
                "/api/v1/auth/register",
                "{\"email\": \"" + email + "\", \"password\": \"secret123\", \"fullName\": \"Flow\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        return login(email);
    }

    private String login(String email) throws Exception {
        MvcResult result =
                postAs(null, "/api/v1/auth/login", "{\"email\": \"" + email + "\", \"password\": \"secret123\"}");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        return "Bearer " + JsonPath.read(body(result), "$.accessToken");
    }
}
