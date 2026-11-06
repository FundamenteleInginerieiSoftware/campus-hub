package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ReservationSeederTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserApi userApi;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Captor
    private ArgumentCaptor<List<Reservation>> reservationsCaptor;

    @Test
    @DisplayName("Skips seeding when reservations table already contains data")
    void skipsWhenReservationsAlreadyExist() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationSeeder seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);

        when(reservationRepository.count()).thenReturn(3L);

        seeder.run();

        verify(reservationRepository, never()).saveAll(any());
        verify(jdbcTemplate, never()).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any());
    }

    @Test
    @DisplayName("Skips seeding when test dev users are not yet seeded")
    void skipsWhenUsersDoNotExist() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationSeeder seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);

        when(reservationRepository.count()).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(10L, 20L));
        when(userApi.exists(1L)).thenReturn(false);

        seeder.run();

        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Skips seeding when assets table is empty")
    void skipsWhenNoAssetsAvailable() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationSeeder seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);

        when(reservationRepository.count()).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of());

        seeder.run();

        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Seeds 2 reservations on the next weekday when DB is clean")
    void seedsReservationsOnCleanDatabase() {
        // Monday 2026-11-02 -> next weekday is Tuesday 2026-11-03
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationSeeder seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);

        when(reservationRepository.count()).thenReturn(0L);
        when(jdbcTemplate.query(
                        org.mockito.ArgumentMatchers.contains("status = 'AVAILABLE'"),
                        org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(10L, 20L));
        when(userApi.exists(1L)).thenReturn(true);
        when(userApi.exists(2L)).thenReturn(true);

        seeder.run();

        verify(reservationRepository).saveAll(reservationsCaptor.capture());
        List<Reservation> saved = reservationsCaptor.getValue();
        assertThat(saved).hasSize(2);

        Reservation res1 = saved.get(0);
        assertThat(res1.getUserId()).isEqualTo(1L);
        assertThat(res1.getAssetId()).isEqualTo(10L);
        assertThat(res1.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res1.getStartTime().getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(res1.getStartTime().getHour()).isEqualTo(10);
        assertThat(res1.getEndTime().getHour()).isEqualTo(12);

        Reservation res2 = saved.get(1);
        assertThat(res2.getUserId()).isEqualTo(2L);
        assertThat(res2.getAssetId()).isEqualTo(10L);
        assertThat(res2.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res2.getStartTime().getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(res2.getStartTime().getHour()).isEqualTo(14);
        assertThat(res2.getEndTime().getHour()).isEqualTo(16);
    }

    @Test
    @DisplayName("Skips weekend days (Saturday/Sunday) and schedules on Monday when run on Friday")
    void skipsWeekendDaysWhenScheduling() {
        // Friday 2026-11-06 -> next weekday is Monday 2026-11-09
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        ReservationSeeder seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);

        when(reservationRepository.count()).thenReturn(0L);
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(10L));
        when(userApi.exists(1L)).thenReturn(true);
        when(userApi.exists(2L)).thenReturn(true);

        seeder.run();

        verify(reservationRepository).saveAll(reservationsCaptor.capture());
        List<Reservation> saved = reservationsCaptor.getValue();
        assertThat(saved.get(0).getStartTime().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(saved.get(0).getStartTime().getDayOfMonth()).isEqualTo(9);
    }
}
