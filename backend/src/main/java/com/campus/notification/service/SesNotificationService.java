package com.campus.notification.service;

import com.campus.notification.api.NotificationService;
import com.campus.user.api.UserApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Real e-mails through Amazon SES, in prod. Liviu's {@code LoggingNotificationService} only writes
 * them to the log (dev/test). Called by Calin's reassignment engine and reservation jobs.
 */
@Slf4j
@Service
@Profile("prod")
public class SesNotificationService implements NotificationService {

    private final SesV2Client ses;
    private final UserApi userApi;
    private final String from;

    public SesNotificationService(SesV2Client ses, UserApi userApi, @Value("${app.mail.from}") String from) {
        this.ses = ses;
        this.userApi = userApi;
        this.from = from;
    }

    @Override
    public void notifyUser(Long userId, String subject, String body) {
        String to = userApi.getById(userId).email();
        // A "simple" e-mail: SES builds the message from a subject and a plain-text body.
        Message message = Message.builder()
                .subject(text(subject))
                .body(Body.builder().text(text(body)).build())
                .build();
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(EmailContent.builder().simple(message).build())
                .build();
        try {
            ses.sendEmail(request);
            log.info("E-mail sent: userId={}, subject={}", userId, subject);
        } catch (SdkException e) {
            // A lost e-mail must not undo the work it reports on: Calin calls this while the moved or
            // cancelled booking is still being saved, and an exception here would roll that back.
            // The reservation itself shows the change, so the user still sees it in "My reservations".
            log.warn("E-mail not sent: userId={}, subject={}: {}", userId, subject, e.getMessage());
        }
    }

    // UTF-8 so Romanian letters (ă, ș, ț) arrive intact.
    private static Content text(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
