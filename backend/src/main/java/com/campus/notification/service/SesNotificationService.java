package com.campus.notification.service;

import com.campus.notification.api.NotificationService;
import com.campus.user.api.UserApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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

    // Bug fixed (#171): SES used to be called on the caller's thread. With SES slow, each e-mail could
    // wait up to the 20 s timeout in AwsConfig, so an asset with 10 bookings held Calin's reassignment
    // for minutes. @Async hands the e-mail to Spring's background pool (spring.task.execution in
    // application.yml) and returns at once. It only works because Calin calls us through the
    // NotificationService bean (Spring's proxy), never from inside this class. The pool's queue has
    // no limit, so an e-mail is never refused, only sent later; e-mails still queued when the app
    // stops are lost (the booking itself is saved, so "My reservations" still shows the change).
    @Async
    @Override
    public void notifyUser(Long userId, String subject, String body) {
        try {
            // Bug fixed (#148): this lookup used to be outside the try, so an unknown user (e.g. deleted
            // meanwhile) threw out of notifyUser. Now it is one more failed e-mail, logged below.
            // UserApi.getById opens its own read-only transaction, so it also works on the background thread.
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
            ses.sendEmail(request);
            log.info("E-mail sent: userId={}, subject={}", userId, subject);
        } catch (RuntimeException e) {
            // A lost e-mail must not undo or stop the work it reports on (a moved or cancelled booking).
            // The reservation itself shows the change, so the user still sees it in "My reservations".
            log.warn("E-mail not sent: userId={}, subject={}: {}", userId, subject, e.getMessage());
        }
    }

    // UTF-8 so Romanian letters (ă, ș, ț) arrive intact.
    private static Content text(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
