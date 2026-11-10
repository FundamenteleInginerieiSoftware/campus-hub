package com.campus.reservation.service;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.ConflictException;
import com.campus.reservation.domain.IdempotencyRecord;
import com.campus.reservation.domain.IdempotencyStatus;
import com.campus.reservation.repository.IdempotencyRecordRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a request at most once per (Idempotency-Key, user). A retry with the same key and body gets
 * the stored response back (header {@code Idempotency-Replayed: true}) instead of booking again.
 */
@Slf4j
@Service
public class IdempotencyService {

    // An IN_PROGRESS row older than this is treated as abandoned (the app crashed or the final
    // save failed) and the key may be used again. Bookings take well under a second, so 60 s is
    // far above a real run. Trade-off: if the crash happened after the booking committed, the
    // retry books again; we accept that instead of locking the student out for 24 h.
    static final Duration ABANDONED_AFTER = Duration.ofSeconds(60);
    static final Duration KEY_LIFETIME = Duration.ofHours(24);
    static final int MAX_KEY_LENGTH = 128;
    // Printable ASCII only, so a key can't break logs or the VARCHAR(128) column.
    private static final Pattern KEY_CHARS = Pattern.compile("[\\x21-\\x7E]+");

    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;

    public IdempotencyService(
            IdempotencyRecordRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        // The key row commits on its own, before the booking runs, so a parallel request sees it.
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String computeFingerprint(Long userId, String method, String path, Object payload) {
        try {
            String payloadJson = payload != null ? objectMapper.writeValueAsString(payload) : "";
            String raw = userId + ":" + method.toUpperCase(Locale.ROOT) + ":" + path + ":" + payloadJson;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** Runs {@code action} once for this key; see the class Javadoc. No key: runs it directly. */
    public <T> ResponseEntity<T> execute(
            String idempotencyKey,
            Long userId,
            String method,
            String path,
            Object payload,
            Class<T> responseType,
            Supplier<ResponseEntity<T>> action) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        // Checked up front: a too-long key used to fail the INSERT and came back as a misleading 409.
        if (idempotencyKey.length() > MAX_KEY_LENGTH
                || !KEY_CHARS.matcher(idempotencyKey).matches()) {
            throw new BadRequestException(
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-128 printable ASCII characters without spaces.");
        }

        String fingerprint = computeFingerprint(userId, method, path, payload);
        // Whole seconds, like every other timestamp the team stores (#130, #135).
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        Optional<ResponseEntity<T>> replay;
        try {
            replay = requiresNewTx.execute(
                    status -> claimKey(idempotencyKey, userId, fingerprint, now, responseType));
        } catch (DataIntegrityViolationException e) {
            // Two first requests raced: the other one inserted the key first.
            throw inProgress();
        }
        if (replay != null && replay.isPresent()) {
            return replay.get();
        }

        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            // FAILED lets the student fix the cause and retry with the same key.
            requiresNewTx.executeWithoutResult(status -> repository
                    .findByIdempotencyKeyAndUserId(idempotencyKey, userId)
                    .ifPresent(IdempotencyRecord::markFailed));
            throw e;
        }

        String json = toJson(response.getBody());
        requiresNewTx.executeWithoutResult(status -> repository
                .findByIdempotencyKeyAndUserId(idempotencyKey, userId)
                .ifPresent(rec -> rec.markCompleted(response.getStatusCode().value(), json)));
        return response;
    }

    // Returns a replay if the key already has an answer, or empty once this request owns the key.
    private <T> Optional<ResponseEntity<T>> claimKey(
            String key, Long userId, String fingerprint, LocalDateTime now, Class<T> responseType) {
        Optional<IdempotencyRecord> found = repository.findByIdempotencyKeyAndUserId(key, userId);
        if (found.isEmpty()) {
            repository.saveAndFlush(new IdempotencyRecord(key, userId, fingerprint, now, now.plus(KEY_LIFETIME)));
            return Optional.empty();
        }

        IdempotencyRecord existing = found.get();
        if (existing.isExpired(now)) {
            existing.restart(fingerprint, now, now.plus(KEY_LIFETIME));
            return Optional.empty();
        }
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new BadRequestException(
                    "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
                    "Idempotency key has already been used with a different request payload.");
        }
        switch (existing.getStatus()) {
            case COMPLETED -> {
                // Never deleted or re-run: that would book a second time.
                return Optional.of(replay(existing, responseType));
            }
            case IN_PROGRESS -> {
                if (existing.getCreatedAt().plus(ABANDONED_AFTER).isAfter(now)) {
                    throw inProgress();
                }
                existing.restart(fingerprint, now, now.plus(KEY_LIFETIME));
                return Optional.empty();
            }
            default -> {
                existing.restart(fingerprint, now, now.plus(KEY_LIFETIME));
                return Optional.empty();
            }
        }
    }

    private <T> ResponseEntity<T> replay(IdempotencyRecord rec, Class<T> responseType) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(rec.getResponseStatus()).header("Idempotency-Replayed", "true");
        if (rec.getResponseBody() == null) {
            return builder.build();
        }
        try {
            return builder.body(objectMapper.readValue(rec.getResponseBody(), responseType));
        } catch (RuntimeException e) {
            // E.g. the response record changed between deploys. Replay the status only.
            log.warn("Stored idempotent response for key {} is unreadable: {}", rec.getIdempotencyKey(), e.getMessage());
            return builder.build();
        }
    }

    private String toJson(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            // The work already succeeded, so we still mark COMPLETED (status-only replay), never FAILED.
            log.warn("Could not store idempotent response body: {}", e.getMessage());
            return null;
        }
    }

    private static ConflictException inProgress() {
        return new ConflictException(
                "CONCURRENT_IDEMPOTENT_REQUEST", "A request with this idempotency key is currently being processed.");
    }

    /** Deletes expired keys every 2 days (configurable). */
    @Scheduled(
            fixedDelayString = "${app.reservation.idempotency-cleanup-ms:172800000}",
            initialDelayString = "${app.reservation.idempotency-cleanup-initial-delay-ms:600000}")
    @Transactional
    public int sweepExpiredRecords() {
        LocalDateTime now = LocalDateTime.now(clock);
        int deleted = repository.deleteExpiredRecords(now);
        if (deleted > 0) {
            log.info("Purged {} expired idempotency records older than {}", deleted, now);
        }
        return deleted;
    }
}
