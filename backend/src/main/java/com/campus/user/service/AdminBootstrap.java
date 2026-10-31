package com.campus.user.service;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first admin on AWS. Register only makes students and the dev seeder doesn't run in
 * prod, so without this nobody could ever use the /api/v1/admin/** endpoints there. Email and
 * password come from ADMIN_EMAIL / ADMIN_PASSWORD (application-prod.yml), set by Matei on AWS.
 */
@Component
@Profile("prod")
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String email;
    private final String password;

    public AdminBootstrap(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${app.admin.email}") String email,
            @Value("${app.admin.password}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        // Both variables default to "" in application-prod.yml, so a deploy without them still starts.
        if (email.isBlank() || password.isBlank()) {
            log.info("ADMIN_EMAIL or ADMIN_PASSWORD not set: no admin account created");
            return;
        }
        // Runs on every start. An existing account is left alone, so a restart never resets the
        // password an admin changed later, and never turns an existing student into an admin.
        if (userRepository.existsByEmailIgnoreCase(email.trim())) {
            log.info("Admin bootstrap skipped: {} already exists", email.trim());
            return;
        }
        // Same limits as RegisterRequest. A bad value stops the app at startup with this message,
        // instead of creating an admin nobody can log in with (BCrypt only reads 72 bytes).
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 8 || bytes > 72) {
            throw new IllegalStateException("ADMIN_PASSWORD must be 8 characters to 72 bytes long");
        }
        userRepository.save(new User(
                email, passwordEncoder.encode(password), "Campus Admin", Role.ADMIN, LocalDateTime.now(clock)));
        // Never log the password, only the email.
        log.info("Created admin account {}", email.trim());
    }
}
