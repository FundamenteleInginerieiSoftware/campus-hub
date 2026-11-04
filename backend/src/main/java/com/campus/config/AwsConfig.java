package com.campus.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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

    @Bean
    S3Client s3Client(@Value("${app.aws-region}") String region) {
        return S3Client.builder().region(Region.of(region)).build();
    }

    @Bean
    SesV2Client sesClient(@Value("${app.aws-region}") String region) {
        return SesV2Client.builder().region(Region.of(region)).build();
    }
}
