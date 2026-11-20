package com.campus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.error.NotFoundException;
import com.campus.notification.web.LiveEvent;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Async;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

// LENIENT: the stub for user 1 in setUp() is not used by the unknown-user test.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SesNotificationServiceTest {

    @Mock
    private SesV2Client ses;

    @Mock
    private UserApi userApi;

    @Mock
    private LiveStreams liveStreams;

    // Fixed campus clock: the live event's time must come from the shared Clock, not the machine's.
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneId.of("Europe/Bucharest"));
    private final LocalDateTime now = LocalDateTime.now(clock);

    private SesNotificationService notifications;

    @BeforeEach
    void setUp() {
        notifications = new SesNotificationService(ses, userApi, liveStreams, clock, "noreply@campus.test");
        when(userApi.getById(1L)).thenReturn(new UserSummary(1L, "student1@campus.test", "Student One", Role.STUDENT));
    }

    @Test
    void sendsTheMailToTheUsersAddress() {
        notifications.notifyUser(1L, "Reservation moved", "Your booking moved to Camera B.");

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest mail = sent.getValue();
        assertThat(mail.fromEmailAddress()).isEqualTo("noreply@campus.test");
        assertThat(mail.destination().toAddresses()).containsExactly("student1@campus.test");
        assertThat(mail.content().simple().subject().data()).isEqualTo("Reservation moved");
        assertThat(mail.content().simple().body().text().data()).isEqualTo("Your booking moved to Camera B.");
    }

    // The caller's booking change must survive a failed e-mail (see the comment in the service).
    @Test
    void aFailedMailDoesNotThrow() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder()
                        .message("Email address is not verified")
                        .build());

        assertThatCode(() -> notifications.notifyUser(1L, "Subject", "Body")).doesNotThrowAnyException();
    }

    // #148: an unknown user (e.g. deleted meanwhile) is one more failed e-mail, not an error for the caller.
    @Test
    void anUnknownUserDoesNotThrowAndSendsNothing() {
        when(userApi.getById(99L)).thenThrow(new NotFoundException("USER_NOT_FOUND", "User not found"));

        assertThatCode(() -> notifications.notifyUser(99L, "Subject", "Body")).doesNotThrowAnyException();
        verifyNoInteractions(ses);
    }

    // #171: the e-mail goes out on a background thread, so a slow SES never holds up Calin's engine.
    @Test
    void notifyUserRunsInTheBackground() throws Exception {
        assertThat(SesNotificationService.class
                        .getMethod("notifyUser", Long.class, String.class, String.class)
                        .isAnnotationPresent(Async.class))
                .isTrue();
    }

    // #290: an open tab gets the notification at once, before the (possibly slow) SES call.
    @Test
    void pushesTheNotificationLiveBeforeTheMail() {
        notifications.notifyUser(1L, "Reservation moved", "Your booking moved to Camera B.");

        InOrder order = inOrder(liveStreams, ses);
        order.verify(liveStreams)
                .send(1L, LiveEvent.notification("Reservation moved", "Your booking moved to Camera B.", now));
        order.verify(ses).sendEmail(any(SendEmailRequest.class));
    }

    // #290: a failed e-mail must not cost the user the live update.
    @Test
    void aFailedMailStillPushesLive() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder().message("throttled").build());

        notifications.notifyUser(1L, "Subject", "Body");

        verify(liveStreams).send(eq(1L), eq(LiveEvent.notification("Subject", "Body", now)));
    }

    // #290: the push needs only the user id, so it still goes out when the e-mail lookup fails.
    @Test
    void anUnknownUserStillGetsTheLivePush() {
        when(userApi.getById(99L)).thenThrow(new NotFoundException("USER_NOT_FOUND", "User not found"));

        notifications.notifyUser(99L, "Subject", "Body");

        verify(liveStreams).send(eq(99L), eq(LiveEvent.notification("Subject", "Body", now)));
        verifyNoInteractions(ses);
    }
}
