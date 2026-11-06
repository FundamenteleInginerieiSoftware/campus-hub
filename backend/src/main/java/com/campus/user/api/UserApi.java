package com.campus.user.api;

import java.util.Optional;

/**
 * The only way other modules read users. They never import {@code User} or {@code UserRepository}:
 * those are internal and may change. Calin's reservations and reassignment, Stefania's incidents and
 * the notifications use this interface; in unit tests, mock it with Mockito.
 */
public interface UserApi {

    /**
     * The user with this id. Used, for example, to find the e-mail address for a notification.
     *
     * @throws com.campus.common.error.NotFoundException with code {@code USER_NOT_FOUND} if no user
     *     has this id
     */
    UserSummary getById(Long id);

    /** {@code true} if a user with this id exists. Cheaper than {@link #getById} for a simple check. */
    boolean exists(Long id);

    /**
     * The user's current role, or empty if the user no longer exists or is disabled. Matei's
     * SecurityConfig calls this on every request with a token, so a blocked account or a changed role
     * counts at once instead of after the token expires (up to 8 h).
     */
    Optional<Role> findActiveRole(Long id);
}
