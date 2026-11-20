package com.campus.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.ConflictException;
import com.campus.notification.web.LiveEvent;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LiveStreamsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 0, 0);

    private LiveStreams liveStreams;

    @BeforeEach
    void setUp() {
        liveStreams = new LiveStreams();
    }

    @Test
    void onlyTheAddressedUserReceives() {
        RecordingEmitter user1Emitter = new RecordingEmitter();
        RecordingEmitter user2Emitter = new RecordingEmitter();
        liveStreams.register(1L, user1Emitter);
        liveStreams.register(2L, user2Emitter);

        liveStreams.send(1L, LiveEvent.notification("Moved", "Booking #12 moved", NOW));

        assertThat(user1Emitter.sentEvents)
                .as("addressed user must receive the event; see LiveStreams.send")
                .hasSize(1);
        assertThat(user2Emitter.sentEvents)
                .as("other users must not receive another user's notification; see LiveStreams.send")
                .isEmpty();
    }

    @Test
    void everyTabOfTheUserReceives() {
        RecordingEmitter tab1 = new RecordingEmitter();
        RecordingEmitter tab2 = new RecordingEmitter();
        liveStreams.register(1L, tab1);
        liveStreams.register(1L, tab2);

        liveStreams.send(1L, LiveEvent.notification("Moved", "Booking #12 moved", NOW));

        assertThat(tab1.sentEvents)
                .as("first tab of user 1 must receive the event; see LiveStreams.send")
                .hasSize(1);
        assertThat(tab2.sentEvents)
                .as("second tab of user 1 must also receive the event; see LiveStreams.send")
                .hasSize(1);
    }

    @Test
    void failingStreamIsDroppedAndOthersStillReceive() {
        BrokenEmitter broken = new BrokenEmitter();
        RecordingEmitter healthy = new RecordingEmitter();
        liveStreams.register(1L, broken);
        liveStreams.register(1L, healthy);

        assertThatCode(() -> liveStreams.send(1L, LiveEvent.notification("Moved", "Booking #12 moved", NOW)))
                .as("a broken stream must not throw to the caller; see LiveStreams.sendSafely")
                .doesNotThrowAnyException();

        assertThat(healthy.sentEvents)
                .as("healthy streams must still receive the event when another stream fails; see LiveStreams.send")
                .hasSize(1);
        assertThat(liveStreams.openCount(1L))
                .as("failing stream must be removed from registry; see LiveStreams.sendSafely")
                .isEqualTo(1);
    }

    @Test
    void sixthStreamIsRefused() {
        for (int i = 0; i < LiveStreams.MAX_STREAMS_PER_USER; i++) {
            liveStreams.register(1L, new RecordingEmitter());
        }

        assertThatThrownBy(() -> liveStreams.register(1L, new RecordingEmitter()))
                .as(
                        "6th concurrent stream for one user must throw ConflictException(LIVE_STREAM_LIMIT); see LiveStreams.register")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("LIVE_STREAM_LIMIT");

        assertThat(liveStreams.openCount(1L))
                .as("refusing the 6th stream must keep the 5 existing streams intact; see LiveStreams.register")
                .isEqualTo(5);
    }

    @Test
    void limitIsPerUser() {
        for (int i = 0; i < LiveStreams.MAX_STREAMS_PER_USER; i++) {
            liveStreams.register(1L, new RecordingEmitter());
        }

        assertThatCode(() -> liveStreams.register(2L, new RecordingEmitter()))
                .as("stream limit for user 1 must not block user 2; see LiveStreams.register")
                .doesNotThrowAnyException();
        assertThat(liveStreams.openCount(2L))
                .as("user 2 should have 1 open stream; see LiveStreams.openCount")
                .isEqualTo(1);
    }

    @Test
    void completedStreamFreesItsSlot() {
        List<RecordingEmitter> emitters = new ArrayList<>();
        for (int i = 0; i < LiveStreams.MAX_STREAMS_PER_USER; i++) {
            RecordingEmitter emitter = new RecordingEmitter();
            liveStreams.register(1L, emitter);
            emitters.add(emitter);
        }

        emitters.get(0).triggerCompletion();

        assertThat(liveStreams.openCount(1L))
                .as("completing a stream must free its slot; see LiveStreams.register")
                .isEqualTo(4);
        assertThatCode(() -> liveStreams.register(1L, new RecordingEmitter()))
                .as("a new stream must succeed once a slot is freed; see LiveStreams.register")
                .doesNotThrowAnyException();
    }

    @Test
    void heartbeatReachesEveryStream() {
        RecordingEmitter u1Tab1 = new RecordingEmitter();
        RecordingEmitter u1Tab2 = new RecordingEmitter();
        RecordingEmitter u2Tab1 = new RecordingEmitter();
        liveStreams.register(1L, u1Tab1);
        liveStreams.register(1L, u1Tab2);
        liveStreams.register(2L, u2Tab1);

        liveStreams.heartbeat();

        assertThat(u1Tab1.sentEvents)
                .as("heartbeat must reach user 1 tab 1; see LiveStreams.heartbeat")
                .hasSize(1);
        assertThat(u1Tab2.sentEvents)
                .as("heartbeat must reach user 1 tab 2; see LiveStreams.heartbeat")
                .hasSize(1);
        assertThat(u2Tab1.sentEvents)
                .as("heartbeat must reach user 2 tab 1; see LiveStreams.heartbeat")
                .hasSize(1);
    }

    @Test
    void sendWithoutAnyStreamDoesNothing() {
        assertThatCode(() -> liveStreams.send(99L, LiveEvent.notification("Title", "Body", NOW)))
                .as("sending to a user with no open streams must be a no-op; see LiveStreams.send")
                .doesNotThrowAnyException();
    }

    @Test
    void concurrentWritesAreNotInterleaved() throws Exception {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEntered = new CountDownLatch(1);
        SseEmitter slowEmitter = new SseEmitter(60_000L) {
            @Override
            public void send(SseEventBuilder builder) {
                trace.add("enter");
                firstEntered.countDown();
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                trace.add("exit");
            }
        };
        liveStreams.register(1L, slowEmitter);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> liveStreams.send(1L, LiveEvent.notification("T", "M", NOW)));
            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> f2 = pool.submit(() -> liveStreams.heartbeat());

            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(trace)
                .as(
                        "writes to the same SseEmitter must be serialized (enter, exit, enter, exit); see LiveStreams.sendSafely")
                .containsExactly("enter", "exit", "enter", "exit");
    }

    private static class RecordingEmitter extends SseEmitter {
        final List<SseEventBuilder> sentEvents = new CopyOnWriteArrayList<>();
        private Runnable completionCallback;

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sentEvents.add(builder);
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            this.completionCallback = callback;
            super.onCompletion(callback);
        }

        void triggerCompletion() {
            if (completionCallback != null) {
                completionCallback.run();
            }
        }
    }

    private static class BrokenEmitter extends SseEmitter {
        BrokenEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("Client closed connection");
        }
    }
}
