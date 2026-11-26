package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.notification.api.NotificationService;
import com.campus.notification.service.SesNotificationService;
import com.campus.storage.api.StorageService;
import com.campus.storage.service.S3StorageService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * Starts the whole app with the prod profile, as on EC2 (#115: it used to stop at startup because
 * no NotificationService existed in prod). The values below stand in for the environment variables
 * application-prod.yml reads. No AWS call happens: the SDK only connects on the first real request.
 */
@SpringBootTest(
        properties = {
            // Same test database as the other tests. In CI, SPRING_DATASOURCE_* override this anyway.
            "DB_URL=jdbc:postgresql://localhost:5432/campus_test",
            "DB_USERNAME=campus",
            "DB_PASSWORD=campus",
            "AWS_REGION=eu-central-1",
            "JWT_SECRET=prod-profile-test-secret-prod-profile-test-secret-0123",
            "PHOTO_BUCKET=campus-photos-test",
            "MAIL_FROM=noreply@campus.test"
        })
@ActiveProfiles("prod")
class ProdProfileStartupTest {

    @Autowired
    StorageService storage;

    @Autowired
    NotificationService notifications;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    ThreadPoolTaskExecutor applicationTaskExecutor;

    @Autowired
    @Qualifier(AsyncConfig.MAIL_EXECUTOR)
    ThreadPoolTaskExecutor mailTaskExecutor;

    @Test
    void prodUsesTheAwsAdapters() {
        assertThat(storage).isInstanceOf(S3StorageService.class);
        assertThat(notifications).isInstanceOf(SesNotificationService.class);
        // #171: a proxy means Spring honours @Async on notifyUser, so e-mails don't block the caller.
        assertThat(AopUtils.isAopProxy(notifications)).isTrue();
    }

    @Test
    void slowMailPoolDoesNotStarveDefaultAsyncPool() throws Exception {
        assertThat(applicationTaskExecutor)
                .as("campus-async- and campus-ses- must be separate pools; see AsyncConfig")
                .isNotSameAs(mailTaskExecutor);
        assertThat(applicationTaskExecutor.getThreadNamePrefix()).isEqualTo("campus-async-");
        assertThat(mailTaskExecutor.getThreadNamePrefix()).isEqualTo("campus-ses-");

        int mailWorkers = mailTaskExecutor.getCorePoolSize();
        CountDownLatch allMailBusy = new CountDownLatch(mailWorkers);
        CountDownLatch releaseMail = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        AtomicReference<String> fastThread = new AtomicReference<>();

        try {
            for (int i = 0; i < mailWorkers; i++) {
                mailTaskExecutor.execute(() -> {
                    allMailBusy.countDown();
                    try {
                        releaseMail.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(allMailBusy.await(2, TimeUnit.SECONDS)).isTrue();

            applicationTaskExecutor.execute(() -> {
                fastThread.set(Thread.currentThread().getName());
                fastDone.countDown();
            });

            assertThat(fastDone.await(2, TimeUnit.SECONDS))
                    .as("default campus-async- task must not block when mailTaskExecutor is full; see AsyncConfig")
                    .isTrue();
            assertThat(fastThread.get()).startsWith("campus-async-");
        } finally {
            releaseMail.countDown();
        }
    }
}
