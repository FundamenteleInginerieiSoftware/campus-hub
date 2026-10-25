package com.campus.user.api;

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
}
