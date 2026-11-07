package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private UserRepository userRepository;

    @Captor
    private ArgumentCaptor<User> saved;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(userRepository, passwordEncoder, clock, email, password);
    }

    @Test
    void createsAdminWithHashedPasswordWhenMissing() {
        // The query ignores case, so only the spaces are removed before it.
        when(userRepository.existsByEmailIgnoreCase("Boss@Campus.test")).thenReturn(false);

        bootstrap(" Boss@Campus.test ", "s3cret-pass").run();

        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getEmail()).isEqualTo("boss@campus.test");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getPasswordHash()).isNotEqualTo("s3cret-pass");
        assertThat(passwordEncoder.matches("s3cret-pass", admin.getPasswordHash()))
                .isTrue();
    }

    @Test
    void leavesAnExistingAccountAlone() {
        when(userRepository.existsByEmailIgnoreCase("boss@campus.test")).thenReturn(true);

        bootstrap("boss@campus.test", "s3cret-pass").run();

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenEmailOrPasswordIsBlank() {
        bootstrap("", "s3cret-pass").run();
        bootstrap("boss@campus.test", "  ").run();

        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesToStartWithATooShortPassword() {
        when(userRepository.existsByEmailIgnoreCase("boss@campus.test")).thenReturn(false);

        assertThatThrownBy(() -> bootstrap("boss@campus.test", "short").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
        verify(userRepository, never()).save(any());
    }

    // The next three tests pin the edges of "8 characters to 72 bytes" (AdminBootstrap.run). Without
    // them, PIT showed that "< 8" could become "<= 8" and "> 72" could become ">= 72" unnoticed.

    @Test
    void acceptsAPasswordOfExactly8Characters() {
        when(userRepository.existsByEmailIgnoreCase("boss@campus.test")).thenReturn(false);

        bootstrap("boss@campus.test", "12345678").run();

        verify(userRepository).save(saved.capture());
        assertThat(passwordEncoder.matches("12345678", saved.getValue().getPasswordHash()))
                .as("8 characters is the minimum and is allowed; see AdminBootstrap.run")
                .isTrue();
    }

    @Test
    void acceptsAPasswordOfExactly72Bytes() {
        // "ă" is 2 bytes in UTF-8: 36 characters = 72 bytes, the most BCrypt reads.
        String password = "ă".repeat(36);
        when(userRepository.existsByEmailIgnoreCase("boss@campus.test")).thenReturn(false);

        bootstrap("boss@campus.test", password).run();

        verify(userRepository).save(saved.capture());
        assertThat(passwordEncoder.matches(password, saved.getValue().getPasswordHash()))
                .as("72 bytes is the maximum and is allowed; see AdminBootstrap.run")
                .isTrue();
    }

    @Test
    void refusesAPasswordOver72BytesEvenWithFewCharacters() {
        // 37 characters, far below 72, but 73 bytes: the limit counts bytes, because BCrypt
        // would silently ignore everything after byte 72.
        String password = "a" + "ă".repeat(36);
        when(userRepository.existsByEmailIgnoreCase("boss@campus.test")).thenReturn(false);

        assertThatThrownBy(() -> bootstrap("boss@campus.test", password).run())
                .as("73 bytes is over the BCrypt limit and stops the start; see AdminBootstrap.run")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD");
        verify(userRepository, never()).save(any());
    }
}
