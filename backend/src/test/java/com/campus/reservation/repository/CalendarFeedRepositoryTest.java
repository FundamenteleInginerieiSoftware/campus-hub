package com.campus.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.reservation.domain.CalendarFeed;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class CalendarFeedRepositoryTest {

    private static final Long USER_ONE_ID = 101L;
    private static final Long USER_TWO_ID = 102L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);
    private static final String HASH_ONE = "a".repeat(64);
    private static final String HASH_TWO = "b".repeat(64);

    @Autowired
    private CalendarFeedRepository calendarFeedRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpUsers() {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
                VALUES (101, 'calfeed1@campus.test', '{bcrypt}hash', 'Feed User One', 'STUDENT', true, NOW())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
                VALUES (102, 'calfeed2@campus.test', '{bcrypt}hash', 'Feed User Two', 'STUDENT', true, NOW())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void findsFeedByTokenHash() {
        calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_ONE_ID, HASH_ONE, NOW));

        Optional<CalendarFeed> found = calendarFeedRepository.findByTokenHash(HASH_ONE);

        assertThat(found)
                .as(
                        "lookup by exact token_hash must return the matching CalendarFeed; see CalendarFeedRepository.findByTokenHash")
                .isPresent();
        assertThat(found.get().getUserId())
                .as("returned CalendarFeed must belong to USER_ONE_ID; see CalendarFeed.userId")
                .isEqualTo(USER_ONE_ID);
    }

    @Test
    void unknownHashIsEmpty() {
        calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_ONE_ID, HASH_ONE, NOW));

        Optional<CalendarFeed> found = calendarFeedRepository.findByTokenHash(HASH_TWO);

        assertThat(found)
                .as("lookup by an unknown token_hash must return empty; see CalendarFeedRepository.findByTokenHash")
                .isEmpty();
    }

    @Test
    void sameHashTwiceIsRefused() {
        calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_ONE_ID, HASH_ONE, NOW));

        assertThatThrownBy(() -> calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_TWO_ID, HASH_ONE, NOW)))
                .as(
                        "calendar_feeds.token_hash UNIQUE constraint must reject duplicate hashes; see V2026_11_16_2205__reservation_create_calendar_feeds.sql")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondRowForSameUserIsRefused() {
        calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_ONE_ID, HASH_ONE, NOW));

        assertThatThrownBy(() -> calendarFeedRepository.saveAndFlush(new CalendarFeed(USER_ONE_ID, HASH_TWO, NOW)))
                .as(
                        "calendar_feeds.user_id UNIQUE constraint must allow at most one feed row per user; see V2026_11_16_2205__reservation_create_calendar_feeds.sql")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
