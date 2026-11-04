package com.campus.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class LoggingNotificationServiceTest {

    @Test
    void notifyUserLogsMessageWithoutThrowing() {
        LoggingNotificationService service = new LoggingNotificationService();

        assertDoesNotThrow(() -> service.notifyUser(1L, "Booking moved", "Your booking was moved to asset #4"));
    }
}
