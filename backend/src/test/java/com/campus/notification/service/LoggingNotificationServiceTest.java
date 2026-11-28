package com.campus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.campus.notification.web.LiveEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(liveStreams).send(eq(12L), captor.capture());

        LiveEvent event = captor.getValue();
        assertThat(event.type())
                .as("dev notifications must emit type='NOTIFICATION'; see LoggingNotificationService.notifyUser")
                .isEqualTo(LiveEvent.TYPE_NOTIFICATION);
        assertThat(event.title())
                .as("dev notification title must match the subject; see LoggingNotificationService.notifyUser")
                .isEqualTo("Reservation Reassigned");
        assertThat(event.message())
                .as("dev notification message must match the body; see LoggingNotificationService.notifyUser")
                .isEqualTo(body);
        assertThat(event.at())
                .as(
                        "dev notification timestamp must come from the shared Clock truncated to seconds; see LoggingNotificationService.notifyUser")
                .isEqualTo(EXPECTED_NOW);
    }
}
