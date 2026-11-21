package com.campus.notification.service;

import com.campus.notification.api.NotificationService;
import com.campus.notification.web.LiveEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Dev/test {@link NotificationService} adapter ({@code @Profile("!prod")}).
 *
 * <p>Logs notifications and pushes them live to any open browser tabs of the recipient via {@link
 * LiveStreams}. Because every notification in dev/test goes through this method, one push call
 * covers Calin's reassignment engine, overdue/no-show jobs, and booking transfers.
 */
@Slf4j
@Service
@Profile("!prod")
@RequiredArgsConstructor
public class LoggingNotificationService implements NotificationService {

    private final LiveStreams liveStreams;
    private final Clock clock;

    @Override
    public void notifyUser(Long userId, String subject, String body) {
        log.info("Notification: userId={}, subject={}, body={}", userId, subject, body);
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        liveStreams.send(userId, LiveEvent.notification(subject, body, now));
    }
}
