package com.campus.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.campus.notification.web.LiveEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class LoggingNotificationServiceTest {

    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Europe/Bucharest");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-21T08:00:00.789Z");
    private static final LocalDateTime EXPECTED_NOW = LocalDateTime.of(2026, 11, 21, 10, 0, 0);

    @Test
    void pushesToTheNotifiedUser() {
        LiveStreams liveStreams = mock(LiveStreams.class);
        Clock clock = Clock.fixed(FIXED_INSTANT, CAMPUS_ZONE);
        LoggingNotificationService service = new LoggingNotificationService(liveStreams, clock);
        String body = "Your booking was moved to asset #4";

        assertDoesNotThrow(() -> service.notifyUser(12L, "Reservation Reassigned", body));

        verify(liveStreams)
                .send(
                        eq(12L),
                        argThat(event -> LiveEvent.TYPE_NOTIFICATION.equals(event.type())
                                && "Reservation Reassigned".equals(event.title())
                                && body.equals(event.message())
                                && EXPECTED_NOW.equals(event.at())));
    }
}
