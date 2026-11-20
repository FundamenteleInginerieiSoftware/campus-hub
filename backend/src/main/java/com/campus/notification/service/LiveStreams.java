package com.campus.notification.service;

import com.campus.common.error.ConflictException;
import com.campus.notification.web.LiveEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of open Server-Sent Events (SSE) streams per user.
 *
 * <p>In-memory storage is sufficient because Campus Hub runs on a single EC2 app instance (ADR-002).
 * Both {@code LoggingNotificationService} (dev/test, Liviu) and {@code SesNotificationService}
 * (prod, Matei) call {@link #send(Long, LiveEvent)} on this bean so every user notification is
 * pushed to open browser tabs without blocking the caller.
 */
@Slf4j
@Component
public class LiveStreams {

    /**
     * Each stream ends after 10 minutes so the browser reconnects through the JWT filter,
     * ensuring expired tokens or disabled accounts lose live updates within 10 minutes.
     */
    public static final Duration STREAM_LIFETIME = Duration.ofMinutes(10);

    /**
     * CloudFront closes idle origin connections after 30 s; sending an SSE comment every 20 s
     * keeps active tabs alive and detects silently closed browser tabs.
     */
    public static final long HEARTBEAT_EVERY_MS = 20_000L;

    /**
     * At most 5 open streams per user; a 6th tab is refused (409) rather than closing the oldest,
     * which would cause 6 open tabs to kick each other out in a reconnect loop.
     */
    public static final int MAX_STREAMS_PER_USER = 5;

    private final ConcurrentHashMap<Long, List<SseEmitter>> streamsByUser = new ConcurrentHashMap<>();

    /** Opens and registers a new SSE stream for the given user. */
    public SseEmitter open(Long userId) {
        return register(userId, new SseEmitter(STREAM_LIFETIME.toMillis()));
    }

    // Package-private entry point so unit tests can inject recording/broken SseEmitter subclasses.
    SseEmitter register(Long userId, SseEmitter emitter) {
        // Check the limit and add under one atomic map computation per user so two tabs opening
        // concurrently cannot both slip through as the 5th stream.
        streamsByUser.compute(userId, (id, existing) -> {
            List<SseEmitter> list = existing != null ? existing : new CopyOnWriteArrayList<>();
            if (list.size() >= MAX_STREAMS_PER_USER) {
                throw new ConflictException("LIVE_STREAM_LIMIT", "Too many open tabs for this account");
            }
            list.add(emitter);
            return list;
        });

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ex -> remove(userId, emitter));
        return emitter;
    }

    /**
     * Pushes a live notification event to every open tab of {@code userId}.
     *
     * <p>Runs {@code @Async} so a slow browser connection can never delay Calin's reassignment
     * engine or scheduled jobs. Works because callers invoke this through Spring's bean proxy.
     */
    @Async
    public void send(Long userId, LiveEvent event) {
        List<SseEmitter> emitters = streamsByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sendSafely(
                    userId, emitter, SseEmitter.event().name("notification").data(event, MediaType.APPLICATION_JSON));
        }
    }

    /** Sends a lightweight SSE comment heartbeat to every open stream. */
    @Scheduled(fixedRate = HEARTBEAT_EVERY_MS)
    public void heartbeat() {
        streamsByUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                sendSafely(userId, emitter, SseEmitter.event().comment("hb"));
            }
        });
    }

    /** Returns the number of currently registered streams for {@code userId}. */
    public int openCount(Long userId) {
        List<SseEmitter> emitters = streamsByUser.get(userId);
        return emitters == null ? 0 : emitters.size();
    }

    /** Completes and removes all currently open streams for {@code userId}. */
    public void completeAll(Long userId) {
        List<SseEmitter> emitters = streamsByUser.remove(userId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            synchronized (emitter) {
                emitter.complete();
            }
        }
    }

    private void sendSafely(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder eventBuilder) {
        // Synchronize per emitter so a scheduled heartbeat and an async notification cannot write
        // to the same underlying HTTP response stream at the exact same instant.
        synchronized (emitter) {
            try {
                emitter.send(eventBuilder);
            } catch (IOException | IllegalStateException ex) {
                log.debug("Dropping closed/broken live stream for userId={}: {}", userId, ex.getMessage());
                remove(userId, emitter);
            }
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        streamsByUser.computeIfPresent(userId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
