package com.campus.user.service;

import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserSummary;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
import com.campus.user.web.UserResponse;
import lombok.RequiredArgsConstructor;
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

    // Copies only what other modules may see: no password hash, no enabled flag.
    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
