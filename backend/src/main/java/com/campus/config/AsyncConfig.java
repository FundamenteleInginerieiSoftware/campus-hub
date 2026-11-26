package com.campus.config;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Switches on two Spring features that are off by default:
 *
 * <ul>
 *   <li>{@code @Async}: the method runs on a background thread, so the caller doesn't wait. Fast
 *       work (Calin's reassignment listeners, Liviu's live SSE pushes) uses {@code campus-async-}
 *       (set in application.yml under {@code spring.task.execution}); slow Amazon SES e-mails in
 *       prod run on their own {@code campus-ses-} pool ({@link #MAIL_EXECUTOR}) so slow mail calls
 *       never starve live toasts or reassignments.
 *   <li>{@code @Scheduled}: the method runs on a timer, e.g. Calin's overdue and no-show jobs and
 *       Liviu's SSE heartbeat. They share a small pool set in application.yml under {@code
 *       spring.task.scheduling}.
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** Dedicated executor bean name for prod SES mail sends ({@code @Async(AsyncConfig.MAIL_EXECUTOR)}). */
    public static final String MAIL_EXECUTOR = "mailTaskExecutor";

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

    /**
     * Default background pool outside tests, built from {@code spring.task.execution.*} in
     * application.yml. Declared explicitly because {@link #mailTaskExecutor()} in prod would
     * otherwise cause Spring Boot's auto-configured {@code applicationTaskExecutor} to back off.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    @Primary
    @Profile("!test")
    ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }

    /**
     * Prod-only pool for Amazon SES calls (up to 20 s timeout in {@link AwsConfig}).
     *
     * <p>Bug fixed: SES previously shared the 2-thread {@code campus-async-} pool (unbounded queue)
     * with {@code LiveStreams.send} and Calin's reassignment listeners, so two slow SES calls
     * blocked all queued live toasts and incident reassignments. Fix: isolate SES on its own pool of
     * 8 core threads that time out when idle; queue stays unbounded so no e-mail is rejected.
     */
    @Bean(name = MAIL_EXECUTOR)
    @Profile("prod")
    ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("campus-ses-");
        executor.setCorePoolSize(8);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
