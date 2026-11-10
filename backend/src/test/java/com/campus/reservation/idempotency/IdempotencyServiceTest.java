package com.campus.reservation.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private IdempotencyService service;

    private final Long userId = 42L;
    private final String key = "test-idempotency-key-12345";
    private final String method = "POST";
    private final String path = "/api/v1/reservations";

    public record SamplePayload(Long assetId, String note) {}

    public record SampleResponse(Long id, String status) {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Bucharest"));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new IdempotencyService(repository, objectMapper, fixedClock, transactionManager);
    }

    @Test
    @DisplayName("Null or blank idempotency key: executes action directly without repository interaction")
    void execute_nullOrBlankKeyPassesThrough() {
        AtomicInteger counter = new AtomicInteger(0);

        ResponseEntity<SampleResponse> res =
                service.execute(null, userId, method, path, new SamplePayload(1L, "note"), SampleResponse.class, () -> {
                    counter.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED).body(new SampleResponse(100L, "CONFIRMED"));
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().id()).isEqualTo(100L);
        assertThat(counter.get()).isEqualTo(1);
        verify(repository, never()).findByIdempotencyKeyAndUserId(any(), any());
    }

    @Test
    @DisplayName("Fresh key: records IN_PROGRESS, executes action, caches COMPLETED response")
    void execute_freshKeyStoresResult() {
        SamplePayload payload = new SamplePayload(10L, "New booking");
        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.empty());

        IdempotencyRecord savedRecord = new IdempotencyRecord(
                key,
                userId,
                service.computeFingerprint(userId, method, path, payload),
                IdempotencyStatus.IN_PROGRESS,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));
        when(repository.save(any())).thenReturn(savedRecord);

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<SampleResponse> res =
                service.execute(key, userId, method, path, payload, SampleResponse.class, () -> {
                    callCount.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED).body(new SampleResponse(200L, "CONFIRMED"));
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().id()).isEqualTo(200L);
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(res.getHeaders().getFirst("Idempotency-Replayed")).isNull();
    }

    @Test
    @DisplayName("Cached replay: returns previously saved response with Idempotency-Replayed header")
    void execute_replaysCachedResponse() {
        SamplePayload payload = new SamplePayload(10L, "New booking");
        String fingerprint = service.computeFingerprint(userId, method, path, payload);

        IdempotencyRecord completedRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.COMPLETED,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));
        completedRecord.markCompleted(201, "{\"id\":200,\"status\":\"CONFIRMED\"}");

        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.of(completedRecord));

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<SampleResponse> res =
                service.execute(key, userId, method, path, payload, SampleResponse.class, () -> {
                    callCount.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED).body(new SampleResponse(999L, "NEW"));
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(res.getBody().id())
                .as("cached body must be returned without calling business logic; see IdempotencyService.execute")
                .isEqualTo(200L);
        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("Payload mismatch: rejects duplicate key used with different request payload")
    void execute_rejectsPayloadMismatch() {
        SamplePayload payload1 = new SamplePayload(10L, "Booking A");
        SamplePayload payload2 = new SamplePayload(10L, "Booking B (different payload)");

        String fingerprint1 = service.computeFingerprint(userId, method, path, payload1);

        IdempotencyRecord completedRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint1,
                IdempotencyStatus.COMPLETED,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));

        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.of(completedRecord));

        assertThatThrownBy(() -> service.execute(
                        key,
                        userId,
                        method,
                        path,
                        payload2,
                        SampleResponse.class,
                        () -> ResponseEntity.ok(new SampleResponse(1L, "OK"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Idempotency key has already been used with a different request payload");
    }

    @Test
    @DisplayName("Concurrent request: rejects second simultaneous request when status is IN_PROGRESS")
    void execute_rejectsConcurrentInProgress() {
        SamplePayload payload = new SamplePayload(10L, "Booking A");
        String fingerprint = service.computeFingerprint(userId, method, path, payload);

        IdempotencyRecord inProgressRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.IN_PROGRESS,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));

        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.of(inProgressRecord));

        assertThatThrownBy(() -> service.execute(
                        key,
                        userId,
                        method,
                        path,
                        payload,
                        SampleResponse.class,
                        () -> ResponseEntity.ok(new SampleResponse(1L, "OK"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently being processed");
    }

    @Test
    @DisplayName("Autonomous purge sweeps expired idempotency records older than current time")
    void sweepExpiredRecords_purgesExpiredRecords() {
        LocalDateTime now = LocalDateTime.now(fixedClock);
        when(repository.deleteExpiredRecords(now)).thenReturn(7);

        int deleted = service.sweepExpiredRecords();

        assertThat(deleted).isEqualTo(7);
        verify(repository).deleteExpiredRecords(now);
    }

    @Test
    @DisplayName("In-progress in isolated transaction: rejects concurrent attempt with ConflictException")
    void execute_rejectsConcurrentInProgressInsideTx() {
        SamplePayload payload = new SamplePayload(10L, "Booking A");
        String fingerprint = service.computeFingerprint(userId, method, path, payload);

        // Outside tx: empty (simulating simultaneous arrival)
        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.empty());

        // Inside tx: already created by concurrent thread
        IdempotencyRecord inProgressRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.IN_PROGRESS,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));
        when(repository.findByIdempotencyKeyAndUserId(key, userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(inProgressRecord));

        assertThatThrownBy(() -> service.execute(
                        key,
                        userId,
                        method,
                        path,
                        payload,
                        SampleResponse.class,
                        () -> ResponseEntity.ok(new SampleResponse(1L, "OK"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently being processed");
    }

    @Test
    @DisplayName("Unique constraint violation: translates DataIntegrityViolationException to ConflictException")
    void execute_translatesDataIntegrityViolationException() {
        SamplePayload payload = new SamplePayload(10L, "Booking A");
        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.empty());
        when(repository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("unique_violation"));

        assertThatThrownBy(() -> service.execute(
                        key,
                        userId,
                        method,
                        path,
                        payload,
                        SampleResponse.class,
                        () -> ResponseEntity.ok(new SampleResponse(1L, "OK"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently being processed");
    }

    @Test
    @DisplayName("Expired record: purges expired entry and allows fresh execution")
    void execute_purgesExpiredRecordAndAllowsFresh() {
        SamplePayload payload = new SamplePayload(10L, "Booking A");
        String fingerprint = service.computeFingerprint(userId, method, path, payload);

        IdempotencyRecord expiredRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.COMPLETED,
                LocalDateTime.now(fixedClock).minusDays(3),
                LocalDateTime.now(fixedClock).minusDays(1)); // Expired yesterday

        when(repository.findByIdempotencyKeyAndUserId(key, userId)).thenReturn(Optional.of(expiredRecord));

        IdempotencyRecord freshRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.IN_PROGRESS,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));
        when(repository.save(any())).thenReturn(freshRecord);

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<SampleResponse> res =
                service.execute(key, userId, method, path, payload, SampleResponse.class, () -> {
                    callCount.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED).body(new SampleResponse(300L, "FRESH"));
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().id()).isEqualTo(300L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Serialization failure on response body: preserves COMPLETED status with null body, preventing duplicate execution")
    void execute_serializationFailurePreservesCompletedStatus() {
        SamplePayload payload = new SamplePayload(10L, "Booking A");

        ObjectMapper brokenMapper = mock(ObjectMapper.class);
        when(brokenMapper.writeValueAsString(any(SamplePayload.class))).thenReturn("{\"assetId\":10}");
        when(brokenMapper.writeValueAsString(any(SampleResponse.class)))
                .thenThrow(new RuntimeException("Serialization boom"));
        IdempotencyService fragileService =
                new IdempotencyService(repository, brokenMapper, fixedClock, transactionManager);

        String fingerprint = fragileService.computeFingerprint(userId, method, path, payload);

        IdempotencyRecord savedRecord = new IdempotencyRecord(
                key,
                userId,
                fingerprint,
                IdempotencyStatus.IN_PROGRESS,
                LocalDateTime.now(fixedClock),
                LocalDateTime.now(fixedClock).plusHours(24));
        when(repository.findByIdempotencyKeyAndUserId(key, userId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedRecord));
        when(repository.save(any())).thenReturn(savedRecord);

        AtomicInteger callCount = new AtomicInteger(0);
        ResponseEntity<SampleResponse> res =
                fragileService.execute(key, userId, method, path, payload, SampleResponse.class, () -> {
                    callCount.incrementAndGet();
                    return ResponseEntity.status(HttpStatus.CREATED).body(new SampleResponse(400L, "OK"));
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(savedRecord.getStatus())
                .as("Status must be COMPLETED even if body serialization failed, avoiding duplicate executions")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(savedRecord.getResponseBody()).isNull();
    }
}
