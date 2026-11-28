package com.campus.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class CatalogConcurrencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogApi catalogApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdAssetIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();
    private final List<Long> createdLabRoomIds = new ArrayList<>();
    private Long createdAdminId;
    private String createdAdminEmail;

    @AfterEach
    void cleanUp() {
        if (createdAdminId != null) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", createdAdminId);
        }
        for (Long id : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM assets WHERE id = ?", id);
        }
        createdAssetIds.clear();
        for (Long id : createdCategoryIds) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", id);
        }
        createdCategoryIds.clear();
        for (Long id : createdLabRoomIds) {
            jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", id);
        }
        createdLabRoomIds.clear();
        if (createdAdminEmail != null) {
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", createdAdminEmail);
            createdAdminEmail = null;
            createdAdminId = null;
        }
    }

    @Test
    void concurrentAssetCreateWithSameSerialReturns201And409Not500() throws Exception {
        String tag = Long.toString(System.nanoTime(), 36);
        String adminToken = registerAndLoginAdmin("cat-race-create-" + tag + "@campus.test");
        long categoryId = createCategory(adminToken, "RaceCat-" + tag);
        long labRoomId = createLabRoom(adminToken, "RC" + tag.substring(Math.max(0, tag.length() - 6)));
        String serial = "RACE-SN-" + tag.toUpperCase();

        String payload = """
                {
                  "name": "Concurrent Scope",
                  "model": "DS1054Z-RACE",
                  "serialNumber": "%s",
                  "categoryId": %d,
                  "labRoomId": %d
                }
                """.formatted(serial, categoryId, labRoomId);

        List<MvcResult> results = runTwoInParallel(() -> mockMvc.perform(post("/api/v1/admin/assets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn());

        List<Integer> statuses =
                results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        for (MvcResult r : results) {
            if (r.getResponse().getStatus() == 201) {
                long id = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
                createdAssetIds.add(id);
            }
        }

        assertThat(statuses)
                .as(
                        "concurrent duplicate asset create must return [201, 409] and never 500; see CatalogPersistence.saveOrThrowConflict")
                .containsExactly(201, 409);

        MvcResult conflict = results.stream()
                .filter(r -> r.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        String code = JsonPath.read(conflict.getResponse().getContentAsString(), "$.code");
        assertThat(code)
                .as("409 response must carry SERIAL_NUMBER_TAKEN; see AssetService.create")
                .isEqualTo("SERIAL_NUMBER_TAKEN");
    }

    @Test
    void concurrentCategoryUpdateToSameNameReturns200And409Not500() throws Exception {
        String tag = Long.toString(System.nanoTime(), 36);
        String adminToken = registerAndLoginAdmin("cat-race-update-" + tag + "@campus.test");
        long catA = createCategory(adminToken, "RaceA-" + tag);
        long catB = createCategory(adminToken, "RaceB-" + tag);
        String targetName = "RaceTarget-" + tag;

        String payload = """
                {
                  "name": "%s",
                  "description": "Renamed concurrently"
                }
                """.formatted(targetName);

        List<MvcResult> results = runTwoInParallel(
                () -> mockMvc.perform(put("/api/v1/admin/categories/" + catA)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn(),
                () -> mockMvc.perform(put("/api/v1/admin/categories/" + catB)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn());

        List<Integer> statuses =
                results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
        assertThat(statuses)
                .as(
                        "concurrent duplicate category rename must return [200, 409] and never 500; see CatalogPersistence.saveOrThrowConflict")
                .containsExactly(200, 409);

        MvcResult conflict = results.stream()
                .filter(r -> r.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        String code = JsonPath.read(conflict.getResponse().getContentAsString(), "$.code");
        assertThat(code)
                .as("409 response must carry CATEGORY_NAME_TAKEN; see CategoryService.update")
                .isEqualTo("CATEGORY_NAME_TAKEN");
    }

    @Test
    void findAvailableWithNullExcludeIdAndBulkFindByIdsWorkOnRealPostgres() throws Exception {
        String tag = Long.toString(System.nanoTime(), 36);
        String adminToken = registerAndLoginAdmin("cat-null-ex-" + tag + "@campus.test");
        long categoryId = createCategory(adminToken, "NullExCat-" + tag);
        long labRoomId = createLabRoom(adminToken, "NE" + tag.substring(Math.max(0, tag.length() - 6)));
        String model = "MODEL-NULL-EX-" + tag;

        long a1 = createAsset(adminToken, "Unit 1", model, "SN-NE-1-" + tag, categoryId, labRoomId);
        long a2 = createAsset(adminToken, "Unit 2", model, "SN-NE-2-" + tag, categoryId, labRoomId);

        List<AssetSummary> byModel = catalogApi.findAvailableByModel(model, null);
        List<AssetSummary> byCategory = catalogApi.findAvailableByCategory(categoryId, null);

        assertThat(byModel)
                .as(
                        "findAvailableByModel(model, null) must return all available units; see CatalogService.findAvailableByModel")
                .extracting(AssetSummary::id)
                .containsExactlyInAnyOrder(a1, a2);
        assertThat(byCategory)
                .as(
                        "findAvailableByCategory(categoryId, null) must return all available units; see CatalogService.findAvailableByCategory")
                .extracting(AssetSummary::id)
                .containsExactlyInAnyOrder(a1, a2);

        Map<Long, AssetSummary> bulk = catalogApi.findAssetsByIds(List.of(a1, a2, 999_999_999L));
        assertThat(bulk)
                .as(
                        "findAssetsByIds must return existing assets keyed by ID and omit missing IDs; see CatalogService.findAssetsByIds")
                .containsOnlyKeys(a1, a2);
    }

    private String registerAndLoginAdmin(String email) throws Exception {
        createdAdminEmail = email;
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "password123", "fullName": "Catalog Race Admin" }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        createdAdminId = ((Number) JsonPath.read(reg.getResponse().getContentAsString(), "$.id")).longValue();
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", createdAdminId);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "%s", "password": "password123" }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    private long createCategory(String token, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "description": "Concurrency test category" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();
        createdCategoryIds.add(id);
        return id;
    }

    private long createLabRoom(String token, String code) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/lab-rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "%s", "name": "Test Lab", "building": "B" }
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();
        createdLabRoomIds.add(id);
        return id;
    }

    private long createAsset(String token, String name, String model, String serial, long categoryId, long labRoomId)
            throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "model": "%s",
                                  "serialNumber": "%s",
                                  "categoryId": %d,
                                  "labRoomId": %d
                                }
                                """.formatted(name, model, serial, categoryId, labRoomId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();
        createdAssetIds.add(id);
        return id;
    }

    private static List<MvcResult> runTwoInParallel(Callable<MvcResult> task) throws Exception {
        return runTwoInParallel(task, task);
    }

    private static List<MvcResult> runTwoInParallel(Callable<MvcResult> first, Callable<MvcResult> second)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> f1 = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            Future<MvcResult> f2 = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
