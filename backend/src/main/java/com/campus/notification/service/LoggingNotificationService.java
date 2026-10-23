package com.campus.notification.service;

import com.campus.notification.api.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("!prod")
public class LoggingNotificationService implements NotificationService {

    @Override
    public void notifyUser(Long userId, String subject, String body) {
        log.info("Notification: userId={}, subject={}, body={}", userId, subject, body);
    }
}
