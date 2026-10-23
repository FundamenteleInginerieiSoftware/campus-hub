package com.campus.notification.api;

public interface NotificationService {
    void notifyUser(Long userId, String subject, String body);
}
