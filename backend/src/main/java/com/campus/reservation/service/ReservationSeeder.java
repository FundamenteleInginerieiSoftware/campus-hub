package com.campus.reservation.service;

import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Seeder for initial development reservations.
// Note: @Order(3) relies on Matei's UserSeeder (@Order(1)) and Liviu's catalog seeder (@Order(2))
// having run first so that valid user and asset rows exist in the database.
@Slf4j
@Component
@Profile("dev")
@Order(3)
@RequiredArgsConstructor
class ReservationSeeder implements CommandLineRunner {

    private static final Long STUDENT_1_ID = 1L;
    private static final Long STUDENT_2_ID = 2L;

    private final ReservationRepository reservationRepository;
    private final UserApi userApi;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public void run(String... args) {
        if (reservationRepository.count() > 0) {
            return;
        }

        List<Long> assetIds =
                jdbcTemplate.query("select id from assets order by id asc limit 2", (rs, rowNum) -> rs.getLong("id"));

        if (!userApi.exists(STUDENT_1_ID) || !userApi.exists(STUDENT_2_ID) || assetIds.isEmpty()) {
            log.info("Skipping reservation seeding: dev users or assets not yet seeded");
            return;
        }

        Long assetId = assetIds.get(0);

        LocalDate nextWeekday = LocalDate.now(clock).plusDays(1);
        while (nextWeekday.getDayOfWeek() == DayOfWeek.SATURDAY || nextWeekday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextWeekday = nextWeekday.plusDays(1);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // One reservation for student1 (id 1) and one for student2 (id 2) so student1 stays below
        // the 2-active-bookings limit for the Swagger prototype demo.
        Reservation res1 =
                new Reservation(STUDENT_1_ID, assetId, nextWeekday.atTime(10, 0), nextWeekday.atTime(12, 0), now);
        Reservation res2 =
                new Reservation(STUDENT_2_ID, assetId, nextWeekday.atTime(14, 0), nextWeekday.atTime(16, 0), now);

        reservationRepository.saveAll(List.of(res1, res2));
        log.info("Seeded sample reservations on asset {} for students {} and {}", assetId, STUDENT_1_ID, STUDENT_2_ID);
    }
}
