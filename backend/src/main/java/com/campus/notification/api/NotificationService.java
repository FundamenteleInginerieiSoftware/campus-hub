package com.campus.notification.api;

/**
 * Sends notifications to users (e.g. when Calin's reassignment engine moves or force-cancels an
 * upcoming reservation after an incident).
 *
 * <p>Implemented by {@code LoggingNotificationService} in dev/test ({@code @Profile("!prod")}) and
 * by Matei's {@code SesNotificationService} in production ({@code @Profile("prod")}).
 */
public interface NotificationService {

    /**
     * Sends a notification with the given subject and body to the user identified by {@code userId}.
     */
    void notifyUser(Long userId, String subject, String body);
}
