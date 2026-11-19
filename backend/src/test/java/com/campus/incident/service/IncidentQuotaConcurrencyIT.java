package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.BusinessRuleException;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.web.CreateIncidentRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #229 review (Matei): the limit of 3 OPEN reports per student must hold when one student sends two reports at
 * the same moment. Built like Calin's ReservationConcurrencyIT (#97, #173).
 *
 * <p>Not @Transactional on purpose: each thread runs reportIncident in its own transaction and must really
 * commit, or the other thread could never see its report. So the data is inserted with plain SQL here and
 * deleted again in tearDown.
 */
@SpringBootTest
class IncidentQuotaConcurrencyIT {

    private static final long STUDENT_ID = 201L;
    private static final long ASSET_A = 711L;
    private static final long ASSET_B = 712L;
    // The two reports the student already has sit on a third asset, so A and B start AVAILABLE.
    private static final long ASSET_WITH_OLD_REPORTS = 713L;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
                VALUES (201, 'student201@incquota.test', '{bcrypt}hash', 'Student 201', 'STUDENT', true, NOW())
                """);
        jdbcTemplate.update("INSERT INTO categories (id, name) VALUES (700, 'Incident quota scopes')");
        jdbcTemplate.update("INSERT INTO lab_rooms (id, code, name) VALUES (700, 'LAB-700', 'Incident quota lab')");
        jdbcTemplate.update("""
                INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (711, 'Quota scope A', 'QUOTA-MODEL', 'SN-QUOTA-711', 'AVAILABLE', 700, 700, NOW(), NOW()),
                       (712, 'Quota scope B', 'QUOTA-MODEL', 'SN-QUOTA-712', 'AVAILABLE', 700, 700, NOW(), NOW()),
                       (713, 'Quota scope C', 'QUOTA-MODEL', 'SN-QUOTA-713', 'AVAILABLE', 700, 700, NOW(), NOW())
                """);
        // The student already has 2 OPEN reports: one more is allowed, two more are not.
        jdbcTemplate.update("""
                INSERT INTO incident_reports (asset_id, reported_by_id, description, severity, status, created_at)
                VALUES (713, 201, 'Old report 1', 'LOW', 'OPEN', NOW()),
                       (713, 201, 'Old report 2', 'LOW', 'OPEN', NOW())
                """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM incident_reports WHERE reported_by_id = ?", STUDENT_ID);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", STUDENT_ID);
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?, ?)", ASSET_A, ASSET_B, ASSET_WITH_OLD_REPORTS);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = 700");
        jdbcTemplate.update("DELETE FROM categories WHERE id = 700");
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", STUDENT_ID);
    }

    @Test
    @DisplayName("Quota lock: a student with 2 OPEN reports sends 2 HIGH reports at once, exactly 1 is saved")
    void twoParallelReportsFromOneStudent_cannotBothPassTheLimit() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        // Both threads wait at the gate, then report at the same moment on two different assets. Different
        // assets lock different rows, so only the per-student quota lock can make them wait for each other.
        LongConsumer reportOn = assetId -> {
            ready.countDown();
            try {
                go.await();
                incidentService.reportIncident(
                        new CreateIncidentRequest(assetId, "Does not turn on", IncidentSeverity.HIGH),
                        STUDENT_ID,
                        false);
                saved.incrementAndGet();
            } catch (BusinessRuleException ex) {
                if ("TOO_MANY_OPEN_INCIDENTS".equals(ex.getCode())) {
                    refused.incrementAndGet();
                } else {
                    unexpected.add(ex);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        };

        // shutdownNow() in finally so a timeout does not leave the threads running.
        try {
            Future<?> first = executor.submit(() -> reportOn.accept(ASSET_A));
            Future<?> second = executor.submit(() -> reportOn.accept(ASSET_B));
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("both threads reached the start gate")
                    .isTrue();
            go.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpected).as("no other error than the limit").isEmpty();
        assertThat(saved.get())
                .as("limit: 2 OPEN + 2 parallel reports, only 1 may pass; "
                        + "see IncidentService.reportIncident (lockReporterQuota before the count)")
                .isEqualTo(1);
        assertThat(refused.get())
                .as("the other report gets 409 TOO_MANY_OPEN_INCIDENTS")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM incident_reports WHERE reported_by_id = ? AND status = 'OPEN'",
                        Long.class,
                        STUDENT_ID))
                .as("the student ends with exactly 3 OPEN reports in the database, never 4")
                .isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM assets WHERE id IN (?, ?) AND status = 'UNDER_MAINTENANCE'",
                        Long.class,
                        ASSET_A,
                        ASSET_B))
                .as("only the asset of the saved report is out of use; the refused one rolled back")
                .isEqualTo(1L);
    }
}
