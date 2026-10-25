package com.campus.user.service;

import com.campus.common.error.NotFoundException;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserSummary;
import com.campus.user.domain.User;
import com.campus.user.repository.UserRepository;
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

    // Copies only what other modules may see: no password hash, no enabled flag.
    private static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
