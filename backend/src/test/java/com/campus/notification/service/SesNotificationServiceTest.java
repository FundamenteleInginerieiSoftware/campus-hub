package com.campus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import com.campus.user.api.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

@ExtendWith(MockitoExtension.class)
class SesNotificationServiceTest {

    @Mock
    private SesV2Client ses;

    @Mock
    private UserApi userApi;

    private SesNotificationService notifications;

    @BeforeEach
    void setUp() {
        notifications = new SesNotificationService(ses, userApi, "noreply@campus.test");
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
}
