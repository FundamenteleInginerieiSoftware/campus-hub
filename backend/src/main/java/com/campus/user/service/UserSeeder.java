package com.campus.user.service;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev accounts for trying the app in Swagger (docs/api-contract.md §3): student1@campus.test,
 * student2@campus.test and admin@campus.test, all with the password "password".
 * Only in the dev profile, so tests and prod never get them.
 */
@Component
@Profile("dev")
@Order(1) // Spring runs runners by @Order: users first, then Liviu's assets, then Calin's ReservationSeeder (@Order(3))
@RequiredArgsConstructor
@Slf4j
public class UserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    public void run(String... args) {
        // Runs on every start: only an empty table is filled, so a restart doesn't add duplicates.
        if (userRepository.count() > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String hash = passwordEncoder.encode("password");
        // Students first, so they get ids 1 and 2: Calin's seeder books for the first two users.
        userRepository.saveAll(List.of(
                new User("student1@campus.test", hash, "Student One", Role.STUDENT, now),
                new User("student2@campus.test", hash, "Student Two", Role.STUDENT, now),
                new User("admin@campus.test", hash, "Campus Admin", Role.ADMIN, now)));
        log.info(
                "Seeded dev users: student1@campus.test, student2@campus.test, admin@campus.test (password: password)");
    }
}
