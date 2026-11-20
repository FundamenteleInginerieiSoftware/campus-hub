package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.ConflictException;
import com.campus.reservation.domain.IdempotencyRecord;
import com.campus.reservation.domain.IdempotencyStatus;
import com.campus.reservation.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class IdempotencyServiceTest {

    private IdempotencyRecordRepository repository;
    private PlatformTransactionManager transactionManager;
    private Clock clock;
    private LocalDateTime now;
    private IdempotencyService service;

    record SamplePayload(Long assetId, String note) {}

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRecordRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Bucharest"));
        now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        service = new IdempotencyService(repository, new ObjectMapper(), clock, transactionManager);
    }

    @Test
    @DisplayName("No key: the action runs directly and the key table is not touched")
    void runsDirectlyWithoutKey() {
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<String> res = service.execute(null, 1L, "POST", "/p", null, String.class, () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body("ok");
        });

        assertThat(res.getStatusCode())
                .as("no Idempotency-Key: plain call; see IdempotencyService.execute")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(calls.get()).as("action must run once without a key").isEqualTo(1);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("A key of exactly 128 characters is accepted; 129 characters is a 400")
    void rejectsTooLongKeyAndAccepts128Chars() {
        String key128 = "k".repeat(128);
        when(repository.findWithLockByIdempotencyKeyAndUserId(key128, 1L)).thenReturn(Optional.empty());

        ResponseEntity<String> ok =
                service.execute(key128, 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("done"));
        assertThat(ok.getBody())
                .as("key of exactly 128 chars must be accepted; see IdempotencyService.MAX_KEY_LENGTH")
                .isEqualTo("done");

        String key129 = "k".repeat(129);
        assertThatThrownBy(() ->
                        service.execute(key129, 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("x")))
                .as("key > 128 chars must be 400 INVALID_IDEMPOTENCY_KEY; see IdempotencyService.execute")
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1-128");
    }

    @Test
    @DisplayName("A key with spaces or non-ASCII characters is a 400")
    void rejectsKeyWithBadCharacters() {
        assertThatThrownBy(() -> service.execute(
                        "has space", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("x")))
                .as("key must be printable ASCII; see IdempotencyService.KEY_CHARS")
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("Fingerprint changes with the body, the user and the path")
    void fingerprintCoversUserPathAndBody() {
        SamplePayload body = new SamplePayload(1L, "a");
        String base = service.computeFingerprint(1L, "POST", "/p", body);

        assertThat(service.computeFingerprint(1L, "post", "/p", body))
                .as("method case must not matter; see computeFingerprint")
                .isEqualTo(base);
        assertThat(service.computeFingerprint(2L, "POST", "/p", body))
                .as("other user -> other fingerprint")
                .isNotEqualTo(base);
        assertThat(service.computeFingerprint(1L, "POST", "/q", body))
                .as("other path -> other fingerprint")
                .isNotEqualTo(base);
        assertThat(service.computeFingerprint(1L, "POST", "/p", new SamplePayload(1L, "b")))
                .as("other body -> other fingerprint")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("First execution claims the key, runs the action, and marks COMPLETED with serialized body")
    void firstExecutionSavesRecordAndMarksCompleted() {
        SamplePayload payload = new SamplePayload(10L, "book");
        String fp = service.computeFingerprint(1L, "POST", "/p", payload);
        IdempotencyRecord claimed = new IdempotencyRecord("k1", 1L, fp, now, now.plusHours(24));

        when(repository.findWithLockByIdempotencyKeyAndUserId("k1", 1L))
                .thenReturn(Optional.empty(), Optional.of(claimed));

        ResponseEntity<SamplePayload> res = service.execute(
                "k1",
                1L,
                "POST",
                "/p",
                payload,
                SamplePayload.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(new SamplePayload(10L, "created")));

        assertThat(res.getStatusCode())
                .as("first execution must return the action's 201 CREATED status; see IdempotencyService.execute")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(claimed.getStatus())
                .as("successful execution must mark IdempotencyRecord COMPLETED; see IdempotencyService.execute")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(claimed.getResponseStatus())
                .as("completed record must persist HTTP status 201; see IdempotencyService.completeRecord")
                .isEqualTo(201);
        assertThat(claimed.getResponseBody())
                .as(
                        "completed record must persist serialized JSON response body; see IdempotencyService.completeRecord")
                .contains("created");
        verify(repository).saveAndFlush(any(IdempotencyRecord.class));
        verify(transactionManager, atLeastOnce())
                .getTransaction(
                        argThat(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    @DisplayName("Null response body (e.g. 204) marks COMPLETED with null stored body")
    void storesNullBodyWhenResponseBodyIsNull() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord emptyBodyRec = new IdempotencyRecord("k-empty", 1L, fp, now, now.plusHours(24));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-empty", 1L))
                .thenReturn(Optional.empty(), Optional.of(emptyBodyRec));

        ResponseEntity<Void> noContent = service.execute(
                "k-empty",
                1L,
                "POST",
                "/p",
                null,
                Void.class,
                () -> ResponseEntity.noContent().build());

        assertThat(noContent.getStatusCode())
                .as("null body action must preserve 204 NO_CONTENT status; see IdempotencyService.execute")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(emptyBodyRec.getStatus())
                .as("null body action must still mark record COMPLETED; see IdempotencyService.toJson")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(emptyBodyRec.getResponseBody())
                .as("null body must be persisted as null, not empty string; see IdempotencyService.toJson")
                .isNull();
    }

    @Test
    @DisplayName("JSON serialization failure still marks COMPLETED with null stored body")
    void storesNullBodyWhenSerializationFails() {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("cannot serialize"));
        IdempotencyService failingService =
                new IdempotencyService(repository, failingMapper, clock, transactionManager);
        String fpFail = failingService.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord serFailRec = new IdempotencyRecord("k-serfail", 1L, fpFail, now, now.plusHours(24));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-serfail", 1L))
                .thenReturn(Optional.empty(), Optional.of(serFailRec));

        ResponseEntity<SamplePayload> res = failingService.execute(
                "k-serfail",
                1L,
                "POST",
                "/p",
                null,
                SamplePayload.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(new SamplePayload(1L, "ok")));

        assertThat(res.getStatusCode())
                .as(
                        "serialization failure must still return the original 201 CREATED response; see IdempotencyService.toJson")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(serFailRec.getStatus())
                .as(
                        "serialization failure after action succeeded must still mark COMPLETED; see IdempotencyService.toJson")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(serFailRec.getResponseBody())
                .as(
                        "serialization failure must persist null body for status-only replay; see IdempotencyService.toJson")
                .isNull();
    }

    @Test
    @DisplayName("When the action throws, the record is marked FAILED and the exception is rethrown")
    void failingActionMarksRecordFailed() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord claimed = new IdempotencyRecord("k-fail", 1L, fp, now, now.plusHours(24));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-fail", 1L))
                .thenReturn(Optional.empty(), Optional.of(claimed));

        assertThatThrownBy(() -> service.execute("k-fail", 1L, "POST", "/p", null, String.class, () -> {
                    throw new IllegalStateException("boom");
                }))
                .as("action failure must propagate after marking record FAILED; see IdempotencyService.execute")
                .isInstanceOf(IllegalStateException.class);
        assertThat(claimed.getStatus())
                .as("failed action must transition record to FAILED so client can retry")
                .isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    @DisplayName("COMPLETED record with stored JSON replays status, Idempotency-Replayed header, and deserialized body")
    void replaysCompletedRecordWithStoredBody() {
        SamplePayload payload = new SamplePayload(1L, "x");
        String fp = service.computeFingerprint(1L, "POST", "/p", payload);
        IdempotencyRecord withBody = new IdempotencyRecord("k-body", 1L, fp, now.minusMinutes(1), now.plusHours(23));
        withBody.markCompleted(201, "{\"assetId\":1,\"note\":\"ok\"}", now.minusMinutes(1));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-body", 1L)).thenReturn(Optional.of(withBody));

        ResponseEntity<SamplePayload> replayed =
                service.execute("k-body", 1L, "POST", "/p", payload, SamplePayload.class, () -> {
                    throw new AssertionError("action must not re-run on replay");
                });

        assertThat(replayed.getStatusCode())
                .as("completed record must replay stored 201 status; see IdempotencyService.replay")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getHeaders().getFirst("Idempotency-Replayed"))
                .as("replayed response must carry Idempotency-Replayed=true header; see IdempotencyService.replay")
                .isEqualTo("true");
        assertThat(replayed.getBody())
                .as("replayed response must deserialize stored JSON body; see IdempotencyService.replay")
                .isEqualTo(new SamplePayload(1L, "ok"));
    }

    @Test
    @DisplayName("COMPLETED record with null stored body replays status without body")
    void replaysCompletedRecordWithNullBody() {
        SamplePayload payload = new SamplePayload(1L, "x");
        String fp = service.computeFingerprint(1L, "POST", "/p", payload);
        IdempotencyRecord nullBody = new IdempotencyRecord("k-null", 1L, fp, now.minusMinutes(1), now.plusHours(23));
        nullBody.markCompleted(204, null, now.minusMinutes(1));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-null", 1L)).thenReturn(Optional.of(nullBody));

        ResponseEntity<SamplePayload> replayedNull =
                service.execute("k-null", 1L, "POST", "/p", payload, SamplePayload.class, () -> null);

        assertThat(replayedNull.getStatusCode())
                .as("completed record with null body must replay stored 204 status; see IdempotencyService.replay")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(replayedNull.getBody())
                .as("completed record with null stored body must replay null body; see IdempotencyService.replay")
                .isNull();
    }

    @Test
    @DisplayName("COMPLETED record with unreadable JSON falls back to status-only replay")
    void replaysCompletedRecordStatusOnlyWhenStoredJsonUnreadable() {
        SamplePayload payload = new SamplePayload(1L, "x");
        String fp = service.computeFingerprint(1L, "POST", "/p", payload);
        IdempotencyRecord badJson = new IdempotencyRecord("k-bad", 1L, fp, now.minusMinutes(1), now.plusHours(23));
        badJson.markCompleted(201, "{not-valid-json", now.minusMinutes(1));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-bad", 1L)).thenReturn(Optional.of(badJson));

        ResponseEntity<SamplePayload> replayedBad =
                service.execute("k-bad", 1L, "POST", "/p", payload, SamplePayload.class, () -> null);

        assertThat(replayedBad.getStatusCode())
                .as("corrupted stored JSON must fall back to status-only replay; see IdempotencyService.replay")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(replayedBad.getBody())
                .as("corrupted stored JSON must return null body instead of failing; see IdempotencyService.replay")
                .isNull();
    }

    @Test
    @DisplayName("Different payload for same key throws 400 IDEMPOTENCY_KEY_PAYLOAD_MISMATCH")
    void rejectsPayloadMismatch() {
        IdempotencyRecord existing =
                new IdempotencyRecord("k-mis", 1L, "other-fp", now.minusMinutes(1), now.plusHours(23));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-mis", 1L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(
                        "k-mis",
                        1L,
                        "POST",
                        "/p",
                        new SamplePayload(2L, "b"),
                        String.class,
                        () -> ResponseEntity.ok("x")))
                .as("reusing key with different payload must throw 400 IDEMPOTENCY_KEY_PAYLOAD_MISMATCH")
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
    }

    @Test
    @DisplayName("Active IN_PROGRESS (< 60s) throws 409 CONCURRENT_IDEMPOTENT_REQUEST")
    void rejectsActiveInProgressRecordWithin60Seconds() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord fresh = new IdempotencyRecord("k-busy", 1L, fp, now.minusSeconds(30), now.plusHours(24));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-busy", 1L)).thenReturn(Optional.of(fresh));

        assertThatThrownBy(() ->
                        service.execute("k-busy", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("x")))
                .as(
                        "active IN_PROGRESS (< 60s) must throw 409 CONCURRENT_IDEMPOTENT_REQUEST; see IdempotencyService.claimOrInspect")
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CONCURRENT_IDEMPOTENT_REQUEST");
    }

    @Test
    @DisplayName("Unique-constraint insert race throws 409 CONCURRENT_IDEMPOTENT_REQUEST")
    void rejectsConcurrentInsertRaceWith409() {
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-race", 1L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() ->
                        service.execute("k-race", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("x")))
                .as(
                        "unique constraint race on insert must throw 409 CONCURRENT_IDEMPOTENT_REQUEST; see IdempotencyService.claimOrInspect")
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CONCURRENT_IDEMPOTENT_REQUEST");
    }

    @Test
    @DisplayName("Expired record restarts with new fingerprint and re-runs action")
    void restartsExpiredRecordAndUpdatesFingerprint() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord expired = new IdempotencyRecord("k-exp", 1L, "old-fp", now.minusHours(25), now.minusHours(1));
        expired.markCompleted(200, "\"old\"", now.minusHours(25));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-exp", 1L)).thenReturn(Optional.of(expired));

        ResponseEntity<String> res =
                service.execute("k-exp", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("fresh"));

        assertThat(res.getBody())
                .as("expired record must re-run action and return fresh body; see IdempotencyService.claimOrInspect")
                .isEqualTo("fresh");
        assertThat(expired.getRequestFingerprint())
                .as(
                        "restarted expired record must replace stale fingerprint with new one; see IdempotencyRecord.restart")
                .isEqualTo(fp);
    }

    @Test
    @DisplayName("Abandoned IN_PROGRESS (>= 60s) record restarts and re-runs action")
    void restartsAbandonedInProgressRecordAfter60Seconds() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord abandoned = new IdempotencyRecord("k-ab", 1L, fp, now.minusSeconds(61), now.plusHours(23));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-ab", 1L)).thenReturn(Optional.of(abandoned));

        ResponseEntity<String> res =
                service.execute("k-ab", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("taken"));

        assertThat(res.getBody())
                .as("abandoned IN_PROGRESS (>= 60s) must re-run action; see IdempotencyService.claimOrInspect")
                .isEqualTo("taken");
        assertThat(abandoned.getStatus())
                .as("taken-over abandoned record must finish in COMPLETED state; see IdempotencyService.completeRecord")
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("FAILED record restarts on retry and transitions to COMPLETED")
    void restartsFailedRecordOnRetry() {
        String fp = service.computeFingerprint(1L, "POST", "/p", null);
        IdempotencyRecord failed = new IdempotencyRecord("k-fl", 1L, fp, now.minusSeconds(10), now.plusHours(23));
        failed.markFailed(now.minusSeconds(10));
        when(repository.findWithLockByIdempotencyKeyAndUserId("k-fl", 1L)).thenReturn(Optional.of(failed));

        ResponseEntity<String> res =
                service.execute("k-fl", 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("retried"));

        assertThat(res.getBody())
                .as(
                        "FAILED record must allow retry and return new response body; see IdempotencyService.claimOrInspect")
                .isEqualTo("retried");
        assertThat(failed.getStatus())
                .as(
                        "retried FAILED record must transition to COMPLETED on success; see IdempotencyService.completeRecord")
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("sweepExpiredRecords returns deleted count for both empty and non-empty sweeps")
    void sweepExpiredRecordsReturnsCount() {
        when(repository.deleteExpiredRecords(now)).thenReturn(0, 3);

        assertThat(service.sweepExpiredRecords())
                .as("empty sweep must return 0 deleted rows; see IdempotencyService.sweepExpiredRecords")
                .isZero();
        assertThat(service.sweepExpiredRecords())
                .as(
                        "sweepExpiredRecords must return the number of deleted expired keys; see IdempotencyService.sweepExpiredRecords")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("IdempotencyRecord.isExpired is false at exact expiresAt boundary and true after")
    void idempotencyRecordIsNotExpiredAtExactBoundaryAndExpiredAfter() {
        IdempotencyRecord rec = new IdempotencyRecord("k", 1L, "fp", now, now.plusHours(1));

        assertThat(rec.isExpired(now.plusHours(1)))
                .as("at exact expiresAt boundary record is not yet expired; see IdempotencyRecord.isExpired")
                .isFalse();
        assertThat(rec.isExpired(now.plusHours(1).plusSeconds(1)))
                .as("after expiresAt record is expired; see IdempotencyRecord.isExpired")
                .isTrue();
    }

    @Test
    @DisplayName("IdempotencyRecord ignores stale attempt timestamps on markCompleted and markFailed")
    void idempotencyRecordIgnoresStaleAttemptTimestampOnCompletionAndFailure() {
        IdempotencyRecord rec = new IdempotencyRecord("k", 1L, "fp", now, now.plusHours(1));

        rec.markFailed(now.minusSeconds(5));
        assertThat(rec.getStatus())
                .as("stale attempt timestamp must not transition record to FAILED; see IdempotencyRecord.markFailed")
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
        rec.markCompleted(200, "x", now.minusSeconds(5));
        assertThat(rec.getStatus())
                .as(
                        "stale attempt timestamp must not transition record to COMPLETED; see IdempotencyRecord.markCompleted")
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("IdempotencyRecord ignores a late markFailed call once COMPLETED")
    void idempotencyRecordIgnoresLateMarkFailedAfterCompleted() {
        IdempotencyRecord rec = new IdempotencyRecord("k", 1L, "fp", now, now.plusHours(1));

        rec.markCompleted(201, "ok", now);
        assertThat(rec.getStatus())
                .as(
                        "matching attempt timestamp must transition record to COMPLETED; see IdempotencyRecord.markCompleted")
                .isEqualTo(IdempotencyStatus.COMPLETED);
        rec.markFailed(now);
        assertThat(rec.getStatus())
                .as("COMPLETED record must ignore a late markFailed call; see IdempotencyRecord.markFailed")
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }
}
