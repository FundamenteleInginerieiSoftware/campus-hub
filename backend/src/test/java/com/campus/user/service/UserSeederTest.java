package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserSeederTest {

    @Mock
    private UserRepository userRepository;

    @Captor
    private ArgumentCaptor<List<User>> saved;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Test
    void seedsTwoStudentsAndOneAdminIntoAnEmptyTable() {
        when(userRepository.count()).thenReturn(0L);

        new UserSeeder(userRepository, passwordEncoder, clock).run();

        verify(userRepository).saveAll(saved.capture());
        assertThat(saved.getValue())
                .extracting(User::getEmail, User::getRole)
                .containsExactly(
                        tuple("student1@campus.test", Role.STUDENT),
                        tuple("student2@campus.test", Role.STUDENT),
                        tuple("admin@campus.test", Role.ADMIN));
        assertThat(saved.getValue()).allMatch(user -> passwordEncoder.matches("password", user.getPasswordHash()));
    }

    @Test
    void doesNothingWhenUsersExist() {
        when(userRepository.count()).thenReturn(3L);

        new UserSeeder(userRepository, passwordEncoder, clock).run();

        verify(userRepository, never()).saveAll(any());
    }
}
