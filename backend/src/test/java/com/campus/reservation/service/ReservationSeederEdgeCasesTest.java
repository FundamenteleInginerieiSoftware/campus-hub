package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserApi;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** The seeder must quietly do nothing when the dev users or assets are not there yet. */
@ExtendWith(MockitoExtension.class)
class ReservationSeederEdgeCasesTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserApi userApi;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ReservationSeeder seeder;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        seeder = new ReservationSeeder(reservationRepository, userApi, jdbcTemplate, clock);
        when(reservationRepository.count()).thenReturn(0L);
    }

    @Test
    void skipsWhenThereAreNoAssetsYet() {
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of());
        when(userApi.exists(1L)).thenReturn(true);
        when(userApi.exists(2L)).thenReturn(true);

        seeder.run();

        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    void skipsWhenTheSecondStudentIsMissing() {
        when(jdbcTemplate.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any()))
                .thenReturn(List.of(10L));
        when(userApi.exists(1L)).thenReturn(true);
        when(userApi.exists(2L)).thenReturn(false);

        seeder.run();

        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsTheAssetIdFromTheIdColumn() throws Exception {
        ArgumentCaptor<RowMapper<Long>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), mapper.capture())).thenReturn(List.of());

        seeder.run();

        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(10L);
        assertThat(mapper.getValue().mapRow(row, 0)).isEqualTo(10L);
    }
}
