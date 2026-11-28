package com.campus.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.NotFoundException;
import com.campus.common.error.UnauthorizedException;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.Role;
import com.campus.user.api.UserSummary;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.ChangePasswordRequest;
import com.campus.user.web.UserResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

// Plain Mockito test: no Spring context and no database, so it runs in milliseconds.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    // Mocked like any other bean: the test decides who is "logged in", no JWT needed.
    @Mock
    private CurrentUser currentUser;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    @Test
    void getByIdReturnsSummaryWithoutPassword() {
        User user = new User(
                "Student1@Campus.test",
                "{bcrypt}hash",
                "Student One",
                Role.STUDENT,
                LocalDateTime.of(2026, 11, 2, 9, 0));
        // The database sets the id on insert; here there is no database, so we set it by hand.
        ReflectionTestUtils.setField(user, "id", 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));

        UserSummary summary = userService.getById(5L);

        assertThat(summary).isEqualTo(new UserSummary(5L, "student1@campus.test", "Student One", Role.STUDENT));
    }

    @Test
    void findActiveRoleGivesTheRoleOfAnEnabledUser() {
        when(userRepository.findActiveRoleById(5L)).thenReturn(Optional.of(Role.ADMIN));

        assertThat(userService.findActiveRole(5L)).contains(Role.ADMIN);
    }

    @Test
    void findActiveRoleIsEmptyForDisabledOrMissingUsers() {
        when(userRepository.findActiveRoleById(5L)).thenReturn(Optional.empty());
        when(userRepository.findActiveRoleById(99L)).thenReturn(Optional.empty());

        assertThat(userService.findActiveRole(5L)).isEmpty();
        assertThat(userService.findActiveRole(99L)).isEmpty();
    }

    @Test
    void getByIdThrowsUserNotFoundForUnknownId() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(99L))
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void existsAsksTheRepository() {
        when(userRepository.existsById(5L)).thenReturn(true);
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThat(userService.exists(5L)).isTrue();
        assertThat(userService.exists(99L)).isFalse();
    }

    @Test
    void meReturnsTheTokensUser() {
        User user = new User(
                "student1@campus.test",
                "{bcrypt}hash",
                "Student One",
                Role.STUDENT,
                LocalDateTime.of(2026, 11, 2, 9, 0));
        ReflectionTestUtils.setField(user, "id", 1L);
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse me = userService.me();

        assertThat(me).isEqualTo(new UserResponse(1L, "student1@campus.test", "Student One", Role.STUDENT));
    }

    @Test
    void meThrowsUserNotFoundWhenTheAccountIsGone() {
        when(currentUser.id()).thenReturn(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.me())
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void changePasswordEncodesNewPasswordUpdatesHashAndWritesAuditLog() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPass123", "{bcrypt}old-hash")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("{bcrypt}new-hash");

        userService.changePassword(new ChangePasswordRequest("oldPass123", "newPass123"));

        assertThat(user.getPasswordHash())
                .as(
                        "valid password change must replace stored hash; see UserService.changePassword and User.changePassword")
                .isEqualTo("{bcrypt}new-hash");
        verify(passwordEncoder).encode("newPass123");
        verify(auditService).record(1L, "USER_PASSWORD_CHANGED", "USER", 1L, "Password changed");
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordWithoutChangingHashOrAuditing() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPass", "{bcrypt}old-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest("wrongPass", "newPass123")))
                .as("wrong current password must throw 401 INVALID_CREDENTIALS; see UserService.changePassword")
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code")
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(user.getPasswordHash())
                .as("failed password change must leave stored hash untouched; see UserService.changePassword")
                .isEqualTo("{bcrypt}old-hash");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void changePasswordRejectsCurrentPasswordOver72Utf8BytesWithoutCallingPasswordEncoder() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        // 37 x "ș" = 74 UTF-8 bytes but only 37 chars; BCrypt would truncate or reject it.
        String overLimit = "ș".repeat(37);

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest(overLimit, "newPass123")))
                .as(
                        "currentPassword over 72 UTF-8 bytes must fail with 401 INVALID_CREDENTIALS before calling BCrypt; see UserService.changePassword")
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code")
                .isEqualTo("INVALID_CREDENTIALS");
        assertThat(user.getPasswordHash())
                .as(
                        "rejected >72-byte current password must leave stored hash untouched; see UserService.changePassword")
                .isEqualTo("{bcrypt}old-hash");
        verifyNoInteractions(passwordEncoder, auditService);
    }

    @Test
    void changePasswordAllowsCurrentPasswordOfExactly72Utf8Bytes() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        String exactly72Bytes = "ș".repeat(36);
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(exactly72Bytes, "{bcrypt}old-hash")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("{bcrypt}new-hash");

        userService.changePassword(new ChangePasswordRequest(exactly72Bytes, "newPass123"));

        assertThat(user.getPasswordHash())
                .as("currentPassword of exactly 72 UTF-8 bytes is within BCrypt limit; see UserService.fitsBcrypt")
                .isEqualTo("{bcrypt}new-hash");
        verify(auditService).record(1L, "USER_PASSWORD_CHANGED", "USER", 1L, "Password changed");
    }

    @Test
    void changePasswordRejectsNewPasswordSameAsCurrent() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("samePass123", "{bcrypt}old-hash")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest("samePass123", "samePass123")))
                .as("reusing the current password must throw 409 PASSWORD_UNCHANGED; see UserService.changePassword")
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code")
                .isEqualTo("PASSWORD_UNCHANGED");
        assertThat(user.getPasswordHash())
                .as("rejected reuse must leave stored hash untouched; see UserService.changePassword")
                .isEqualTo("{bcrypt}old-hash");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void changePasswordChecksCurrentPasswordBeforeRejectingReuse() {
        User user = studentWithId(1L, "{bcrypt}old-hash");
        when(currentUser.id()).thenReturn(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPass123", "{bcrypt}old-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest("wrongPass123", "wrongPass123")))
                .as(
                        "wrong current password must return 401 INVALID_CREDENTIALS even when newPassword equals it; see UserService.changePassword")
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code")
                .isEqualTo("INVALID_CREDENTIALS");
        verify(passwordEncoder, never()).encode(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void changePasswordThrowsUserNotFoundWhenAccountIsMissing() {
        when(currentUser.id()).thenReturn(42L);
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(new ChangePasswordRequest("oldPass123", "newPass123")))
                .as("missing user row must throw 404 USER_NOT_FOUND; see UserService.changePassword")
                .isInstanceOf(NotFoundException.class)
                .extracting("code")
                .isEqualTo("USER_NOT_FOUND");
        verifyNoInteractions(passwordEncoder, auditService);
    }

    private static User studentWithId(Long id, String passwordHash) {
        User user = new User(
                "student1@campus.test", passwordHash, "Student One", Role.STUDENT, LocalDateTime.of(2026, 11, 2, 9, 0));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
