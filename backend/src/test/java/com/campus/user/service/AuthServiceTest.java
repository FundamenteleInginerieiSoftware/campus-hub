package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.error.ConflictException;
import com.campus.common.error.UnauthorizedException;
import com.campus.user.api.Role;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.LoginRequest;
import com.campus.user.web.LoginResponse;
import com.campus.user.web.RegisterRequest;
import com.campus.user.web.UserResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

// Mockito test without Spring: only the repository is mocked. The password encoder and the JWT
// encoder are the real ones, so the test proves a real hash is stored and a real token comes out.
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    private static final SecretKey KEY = new SecretKeySpec(
            "test-only-secret-change-me-test-only-secret-change-me-012345678".getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");

    @Mock
    private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Europe/Bucharest"));
    private AuthService authService;

    @BeforeEach
    void setUp() {
        NimbusJwtEncoder jwtEncoder = NimbusJwtEncoder.withSecretKey(KEY)
                .algorithm(MacAlgorithm.HS256)
                .build();
        authService = new AuthService(userRepository, passwordEncoder, jwtEncoder, clock, Duration.ofHours(8));
    }

    @Test
    void registerCreatesStudentWithHashedPassword() {
        when(userRepository.existsByEmailIgnoreCase("New@Campus.test")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> withId(call.getArgument(0), 7L));

        UserResponse response = authService.register(new RegisterRequest(" New@Campus.test ", "secret123", "New One"));

        assertThat(response).isEqualTo(new UserResponse(7L, "new@campus.test", "New One", Role.STUDENT));
        verify(userRepository)
                .saveAndFlush(argThat(saved -> saved.getPasswordHash().startsWith("{bcrypt}")
                        && passwordEncoder.matches("secret123", saved.getPasswordHash())));
    }

    @Test
    void registerRejectsTakenEmail() {
        when(userRepository.existsByEmailIgnoreCase("student1@campus.test")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("student1@campus.test", "secret123", "Copy")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("EMAIL_TAKEN");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerTurnsDuplicateInsertInto409() {
        when(userRepository.existsByEmailIgnoreCase("race@campus.test")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("race@campus.test", "secret123", "Race")))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void loginReturnsTokenWithContractClaims() {
        User user = withId(
                new User("admin@campus.test", passwordEncoder.encode("password"), "Admin", Role.ADMIN, now()), 1L);
        when(userRepository.findByEmailIgnoreCase("Admin@Campus.test")).thenReturn(Optional.of(user));

        LoginResponse response = authService.login(new LoginRequest("Admin@Campus.test", "password"));

        assertThat(response.expiresIn()).isEqualTo(28800);
        assertThat(response.user()).isEqualTo(new UserResponse(1L, "admin@campus.test", "Admin", Role.ADMIN));
        Jwt token = decode(response.accessToken());
        assertThat(token.getSubject()).isEqualTo("1");
        assertThat(token.getClaimAsString("email")).isEqualTo("admin@campus.test");
        assertThat(token.getClaimAsStringList("roles")).isEqualTo(List.of("ADMIN"));
        assertThat(token.getIssuedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = withId(
                new User("student1@campus.test", passwordEncoder.encode("password"), "S", Role.STUDENT, now()), 2L);
        when(userRepository.findByEmailIgnoreCase("student1@campus.test")).thenReturn(Optional.of(user));

        assertInvalidCredentials(new LoginRequest("student1@campus.test", "wrong-password"));
    }

    @Test
    void loginRejectsUnknownEmailWithTheSameError() {
        when(userRepository.findByEmailIgnoreCase("nobody@campus.test")).thenReturn(Optional.empty());

        assertInvalidCredentials(new LoginRequest("nobody@campus.test", "password"));
    }

    @Test
    void loginRejectsDisabledUserWithTheSameError() {
        User user =
                withId(new User("old@campus.test", passwordEncoder.encode("password"), "Old", Role.STUDENT, now()), 3L);
        // No method disables a user yet (Stefania's user admin will add one), so set the field directly.
        ReflectionTestUtils.setField(user, "enabled", false);
        when(userRepository.findByEmailIgnoreCase("old@campus.test")).thenReturn(Optional.of(user));

        assertInvalidCredentials(new LoginRequest("old@campus.test", "password"));
    }

    private void assertInvalidCredentials(LoginRequest request) {
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code")
                .isEqualTo("INVALID_CREDENTIALS");
    }

    // Checks the signature with the same key, and "exp" against the fixed clock instead of the real time.
    private Jwt decode(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator expiry = new JwtTimestampValidator();
        expiry.setClock(clock);
        decoder.setJwtValidator(expiry);
        return decoder.decode(token);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // The database sets the id on insert; here there is no database, so we set it by hand.
    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
