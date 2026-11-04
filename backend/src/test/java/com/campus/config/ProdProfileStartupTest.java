package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.notification.api.NotificationService;
import com.campus.notification.service.SesNotificationService;
import com.campus.storage.api.StorageService;
import com.campus.storage.service.S3StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void prodUsesTheAwsAdapters() {
        assertThat(storage).isInstanceOf(S3StorageService.class);
        assertThat(notifications).isInstanceOf(SesNotificationService.class);
    }
}
