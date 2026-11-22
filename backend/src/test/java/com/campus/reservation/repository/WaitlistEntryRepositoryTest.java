package com.campus.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class WaitlistEntryRepositoryTest {

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long user1Id;
    private Long user2Id;
    private Long assetId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        user1Id = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', 'WL User 1', 'STUDENT', true, NOW())
            RETURNING id
            """, Long.class, "wl-repo-1-" + tag + "@campus.test");
        user2Id = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', 'WL User 2', 'STUDENT', true, NOW())
            RETURNING id
            """, Long.class, "wl-repo-2-" + tag + "@campus.test");
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "WL-Repo-Cat-" + tag);
        Long labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "WLR-" + tag,
                "WL Repo Lab " + tag);
        assetId =
                jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, 'DS1054Z', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, "WL Scope " + tag, "SN-WLR-" + tag, categoryId, labRoomId);
    }

    @Test
    @DisplayName("findByAssetIdAndStatusForUpdate returns WAITING entries in strict FIFO (createdAt, id) order")
    void findByAssetIdAndStatusForUpdate_returnsFifoOrder() {
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = start.plusHours(2);

        WaitlistEntry newer = waitlistEntryRepository.saveAndFlush(
                new WaitlistEntry(assetId, user2Id, start, end, now.minusMinutes(2)));
        WaitlistEntry older = waitlistEntryRepository.saveAndFlush(
                new WaitlistEntry(assetId, user1Id, start, end, now.minusMinutes(10)));
        WaitlistEntry cancelled = new WaitlistEntry(assetId, user1Id, start.plusHours(2), end.plusHours(2), now);
        cancelled.cancel(now);
        waitlistEntryRepository.saveAndFlush(cancelled);

        List<WaitlistEntry> result =
                waitlistEntryRepository.findByAssetIdAndStatusForUpdate(assetId, WaitlistStatus.WAITING);

        assertThat(result)
                .as(
                        "FIFO waitlist query must order WAITING entries by createdAt ASC then id ASC; see WaitlistEntryRepository.findByAssetIdAndStatusForUpdate")
                .extracting(WaitlistEntry::getId)
                .containsExactly(older.getId(), newer.getId());
    }

    @Test
    @DisplayName("expireOldEntries expires past-start WAITING rows, bumps version, and keeps future entries")
    void expireOldEntries_expiresPastEntriesAndBumpsVersion() {
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK).truncatedTo(ChronoUnit.SECONDS);

        WaitlistEntry past = waitlistEntryRepository.saveAndFlush(
                new WaitlistEntry(assetId, user1Id, now.minusHours(1), now.plusHours(1), now.minusHours(3)));
        WaitlistEntry future = waitlistEntryRepository.saveAndFlush(
                new WaitlistEntry(assetId, user2Id, now.plusHours(2), now.plusHours(4), now.minusHours(1)));

        int expiredCount = waitlistEntryRepository.expireOldEntries(now);
        entityManager.clear();

        WaitlistEntry reloadedPast =
                waitlistEntryRepository.findById(past.getId()).orElseThrow();
        WaitlistEntry reloadedFuture =
                waitlistEntryRepository.findById(future.getId()).orElseThrow();

        assertThat(expiredCount)
                .as("expireOldEntries must update only past-start WAITING entries")
                .isEqualTo(1);
        assertThat(reloadedPast.getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
        assertThat(reloadedPast.getVersion())
                .as("bulk expireOldEntries must bump @Version column; see WaitlistEntryRepository.expireOldEntries")
                .isEqualTo(1L);
        assertThat(reloadedFuture.getStatus()).isEqualTo(WaitlistStatus.WAITING);
    }
}
