package com.campus.user.service;

import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.NotFoundException;
import com.campus.common.error.UnauthorizedException;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserSummary;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.ChangePasswordRequest;
import com.campus.user.web.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real {@link UserApi}. Other modules get this bean when they inject {@code UserApi}: Calin's
 * reservations check the booking's owner, Stefania's incidents the reporter, and notifications look
 * up the e-mail address.
 */
@Service
@RequiredArgsConstructor
public class UserService implements UserApi {

    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    // The entity is turned into a UserSummary inside the transaction: open-in-view is off, so the
    // User must not be used after this method returns.
    @Override
    @Transactional(readOnly = true)
    public UserSummary getById(Long id) {
        return userRepository
                .findById(id)
                .map(UserService::toSummary)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + id + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(Long id) {
        return userRepository.existsById(id);
    }

    // One primary-key lookup per request with a token (see SecurityConfig).
    // Bug fixed: this used findById, which inside the caller's transaction returned the User that was
    // already loaded, so Calin's re-check after the per-user quota lock (ReservationService, HoldService,
    // TransferService) still saw enabled = true for an account disabled in the meantime.
    // Fix: a scalar query reads the row from the database every time, and it is still one
    // primary-key lookup per request.
    @Override
    @Transactional(readOnly = true)
    public Optional<Role> findActiveRole(Long id) {
        return userRepository.findActiveRoleById(id);
    }

    // GET /api/v1/users/me. The id comes from the token (Stefania's CurrentUser), never from the request,
    // so nobody can ask for someone else's account. The frontend calls it after a page reload to know who
    // is logged in. 404 USER_NOT_FOUND if the token is still valid but the account is gone (database reset).
    @Transactional(readOnly = true)
    public UserResponse me() {
        Long id = currentUser.id();
        return userRepository
                .findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + id + " not found"));
    }

    // PUT /api/v1/users/me/password. The id always comes from the token (CurrentUser), never from the
    // request body. Checks the current password before the reuse check so a caller cannot probe
    // guesses against 409 PASSWORD_UNCHANGED instead of 401 INVALID_CREDENTIALS.
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long id = currentUser.id();
        User user = userRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + id + " not found"));

        // Same 72-byte BCrypt guard as AuthService.login: BCrypt ignores everything past 72 UTF-8
        // bytes, so a longer input can never be the real password and must fail without calling it.
        if (!fitsBcrypt(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Current password is incorrect");
        }

        if (request.newPassword().equals(request.currentPassword())) {
            throw new BusinessRuleException(
                    "PASSWORD_UNCHANGED", "New password must be different from the current password");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        // Never record passwords or hashes in details: admins can read the audit log.
        auditService.record(id, "USER_PASSWORD_CHANGED", "USER", id, "Password changed");
    }

    // Same UTF-8 byte limit as RegisterRequest and AuthService.login: "ș" counts as 2 bytes.
    private static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    // Copies only what other modules may see: no password hash, no enabled flag.
    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
