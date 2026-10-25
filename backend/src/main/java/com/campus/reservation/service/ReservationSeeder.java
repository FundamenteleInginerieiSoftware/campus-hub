package com.campus.reservation.service;

import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
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

@Slf4j
@Component
@Profile("dev")
@Order(3)
@RequiredArgsConstructor
class ReservationSeeder implements CommandLineRunner {

    private final ReservationRepository reservationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public void run(String... args) {
        if (reservationRepository.count() > 0) {
            return;
        }

        List<Long> userIds =
                jdbcTemplate.query("select id from users order by id asc limit 2", (rs, rowNum) -> rs.getLong("id"));
        List<Long> assetIds =
                jdbcTemplate.query("select id from assets order by id asc limit 2", (rs, rowNum) -> rs.getLong("id"));

        if (userIds.isEmpty() || assetIds.isEmpty()) {
            log.info("Skipping reservation seeding: no users or assets found");
            return;
        }

        Long userId = userIds.get(0);
        Long assetId = assetIds.get(0);

        LocalDate nextWeekday = LocalDate.now(clock).plusDays(1);
        while (nextWeekday.getDayOfWeek() == DayOfWeek.SATURDAY || nextWeekday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            nextWeekday = nextWeekday.plusDays(1);
        }

        LocalDateTime now = LocalDateTime.now(clock);

        Reservation res1 = new Reservation(userId, assetId, nextWeekday.atTime(10, 0), nextWeekday.atTime(12, 0), now);

        Reservation res2 = new Reservation(userId, assetId, nextWeekday.atTime(14, 0), nextWeekday.atTime(16, 0), now);

        reservationRepository.saveAll(List.of(res1, res2));
        log.info("Seeded sample reservations on asset {} for user {}", assetId, userId);
    }
}
