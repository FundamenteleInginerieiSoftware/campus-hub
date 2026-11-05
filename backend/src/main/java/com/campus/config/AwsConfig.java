package com.campus.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * The AWS clients, only in prod. No keys anywhere: on EC2 the SDK finds the instance role's temporary
 * credentials by itself (ADR-002), and it only looks for them on the first real call, so the app
 * also starts on a laptop with the prod profile.
 */
@Configuration
@Profile("prod")
public class AwsConfig {

    // The SDK has no overall time limit by default. Without one, a slow S3 or SES call would hold
    // the request thread (and, for e-mails sent by Calin's engine, its database transaction) for as
    // long as the network hangs. 10 s per attempt, 20 s in total including the SDK's retries.
    private static final ClientOverrideConfiguration TIMEOUTS = ClientOverrideConfiguration.builder()
            .apiCallAttemptTimeout(Duration.ofSeconds(10))
            .apiCallTimeout(Duration.ofSeconds(20))
            .build();

    @Bean
    S3Client s3Client(@Value("${app.aws-region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(TIMEOUTS)
                .build();
    }

    @Bean
    SesV2Client sesClient(@Value("${app.aws-region}") String region) {
        return SesV2Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(TIMEOUTS)
                .build();
    }
}
