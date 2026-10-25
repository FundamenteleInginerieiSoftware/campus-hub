package com.campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on two Spring features that are off by default:
 *
 * <ul>
 *   <li>{@code @Async}: the method runs on a background thread, so the caller doesn't wait. Used for
 *       event listeners such as Calin's reassignment and the notifications. The pool itself (size,
 *       thread names) is set in application.yml under {@code spring.task.execution}.
 *   <li>{@code @Scheduled}: the method runs on a timer, e.g. Calin's overdue and no-show jobs.
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Tests only: {@code @Async} methods run on the test's own thread, so when the call returns the
     * work is already done. Otherwise a test would have to wait and guess when the background thread
     * finishes (a "flaky" test). Defining this bean also replaces Spring Boot's thread pool.
     */
    @Bean
    @Profile("test")
    TaskExecutor taskExecutor() {
        return new SyncTaskExecutor();
    }
}
