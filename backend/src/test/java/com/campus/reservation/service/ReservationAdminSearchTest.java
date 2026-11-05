package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.paging.PageResponse;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.ReservationResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin search builds its WHERE clause with a JPA Specification, so it is tested against the
 * real PostgreSQL: every filter must really narrow the result, and no filter must return everything.
 */
@DataJpaTest
class ReservationAdminSearchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ReservationService reservationService;
    private Reservation onAsset1Student1;
    private Reservation onAsset2Student2;
    private Reservation cancelledNextWeek;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (210, 'search210@campus.test', '{bcrypt}hash', 'Search 210', 'STUDENT', true, NOW()),
                   (211, 'search211@campus.test', '{bcrypt}hash', 'Search 211', 'STUDENT', true, NOW())
            """);
        jdbcTemplate.update("INSERT INTO categories (id, name) VALUES (210, 'Search Category')");
        jdbcTemplate.update("INSERT INTO lab_rooms (id, code, name) VALUES (210, 'LAB-210', 'Search Lab')");
        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (210, 'Search Asset 1', 'M1', 'SN-SEARCH-210', 'AVAILABLE', 210, 210, NOW(), NOW()),
                   (211, 'Search Asset 2', 'M1', 'SN-SEARCH-211', 'AVAILABLE', 210, 210, NOW(), NOW())
            """);

        LocalDateTime created = LocalDateTime.of(2026, 11, 1, 9, 0);
        onAsset1Student1 =
                reservationRepository.save(new Reservation(210L, 210L, DAY.atTime(10, 0), DAY.atTime(12, 0), created));
        onAsset2Student2 =
                reservationRepository.save(new Reservation(211L, 211L, DAY.atTime(14, 0), DAY.atTime(16, 0), created));
        cancelledNextWeek = new Reservation(
                210L, 211L, DAY.plusDays(7).atTime(10, 0), DAY.plusDays(7).atTime(12, 0), created);
        cancelledNextWeek.cancel(created, "test");
        reservationRepository.saveAndFlush(cancelledNextWeek);

        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        reservationService = new ReservationService(
                reservationRepository,
                mock(CatalogApi.class),
                mock(CurrentUser.class),
                mock(AuditService.class),
                clock);
    }

    private PageResponse<ReservationResponse> search(
            ReservationStatus status, Long userId, Long assetId, LocalDate date) {
        return reservationService.getAdminReservations(status, userId, assetId, date, 0, 20);
    }

    @Test
    void withoutFiltersEveryReservationIsReturned() {
        assertThat(search(null, null, null, null).content())
                .extracting(ReservationResponse::id)
                .contains(onAsset1Student1.getId(), onAsset2Student2.getId(), cancelledNextWeek.getId());
    }

    @Test
    void filtersByStatus() {
        assertThat(search(ReservationStatus.CANCELLED, 210L, null, null).content())
                .extracting(ReservationResponse::id)
                .containsExactly(cancelledNextWeek.getId());
    }

    @Test
    void filtersByStudent() {
        assertThat(search(null, 211L, null, null).content())
                .extracting(ReservationResponse::id)
                .containsExactly(onAsset2Student2.getId());
    }

    @Test
    void filtersByAsset() {
        assertThat(search(null, null, 211L, null).content())
                .extracting(ReservationResponse::id)
                .containsExactlyInAnyOrder(onAsset2Student2.getId(), cancelledNextWeek.getId());
    }

    @Test
    void filtersByDayAndNewestStartComesFirst() {
        assertThat(search(null, null, null, DAY).content())
                .extracting(ReservationResponse::id)
                .containsExactly(onAsset2Student2.getId(), onAsset1Student1.getId());
    }

    @Test
    void combinesAllFilters() {
        assertThat(search(ReservationStatus.CONFIRMED, 210L, 210L, DAY).content())
                .extracting(ReservationResponse::id)
                .containsExactly(onAsset1Student1.getId());
    }
}
