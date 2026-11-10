package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.campus.common.error.BadRequestException;
import com.campus.reservation.repository.IdempotencyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

// Input checks only. The key lifecycle (replay, FAILED retry, abandoned IN_PROGRESS) needs real
// transactions and the unique index, so it is tested against PostgreSQL in IdempotencyServiceIT.
class IdempotencyServiceTest {

    private IdempotencyRecordRepository repository;
    private IdempotencyService service;

    record SamplePayload(Long assetId, String note) {}

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRecordRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Bucharest"));
        service = new IdempotencyService(repository, new ObjectMapper(), clock, mock(PlatformTransactionManager.class));
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
    @DisplayName("A key longer than 128 characters is a 400, not a misleading 409")
    void rejectsTooLongKey() {
        String key = "k".repeat(129);

        assertThatThrownBy(
                        () -> service.execute(key, 1L, "POST", "/p", null, String.class, () -> ResponseEntity.ok("x")))
                .as("key > 128 chars must be 400 INVALID_IDEMPOTENCY_KEY; see IdempotencyService.execute")
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1-128");
        verifyNoInteractions(repository);
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
}
