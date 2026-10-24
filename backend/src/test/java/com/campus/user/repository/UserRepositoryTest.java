package com.campus.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

// Runs Flyway against the local campus_test database, so a mismatch between the migration and the
// entity fails here. Each test is rolled back afterwards.
@DataJpaTest
class UserRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 15, 0);

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailIgnoringCase() {
        userRepository.save(new User(" Student1@Campus.test ", "{bcrypt}hash", "Student One", Role.STUDENT, NOW));

        assertThat(userRepository.findByEmailIgnoreCase("STUDENT1@campus.test"))
                .get()
                .extracting(User::getEmail)
                .isEqualTo("student1@campus.test");
        assertThat(userRepository.existsByEmailIgnoreCase("student1@campus.test"))
                .isTrue();
    }

    @Test
    void rejectsSameEmailWithDifferentCase() {
        userRepository.saveAndFlush(new User("ana@campus.test", "{bcrypt}hash", "Ana", Role.STUDENT, NOW));

        // saveAndFlush sends the INSERT now, so the UNIQUE constraint fires inside the test.
        assertThatThrownBy(() -> userRepository.saveAndFlush(
                        new User("ANA@campus.test", "{bcrypt}hash", "Ana Two", Role.STUDENT, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
