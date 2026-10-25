package com.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;

@SpringBootTest
class AsyncConfigTest {

    @Autowired
    ThreadRecorder recorder;

    @Test
    void asyncMethodsRunOnTheTestThreadInTests() {
        // Spring wraps the bean in a proxy only when @EnableAsync is on.
        assertThat(AopUtils.isAopProxy(recorder)).isTrue();

        recorder.record();

        // Same thread as the test = the synchronous executor from the "test" profile was used.
        assertThat(recorder.threadName()).isEqualTo(Thread.currentThread().getName());
    }

    // A tiny @Async bean that exists only in this test.
    static class ThreadRecorder {
        private volatile String threadName;

        @Async
        public void record() {
            threadName = Thread.currentThread().getName();
        }

        // Bug fixed: the test first read the field directly (recorder.threadName) and always got
        // null. "recorder" is Spring's proxy, not the real object, and the proxy's own copy of the
        // field is never filled. Fix: read it through this method, because the proxy passes
        // method calls on to the real object.
        public String threadName() {
            return threadName;
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        ThreadRecorder threadRecorder() {
            return new ThreadRecorder();
        }
    }
}
