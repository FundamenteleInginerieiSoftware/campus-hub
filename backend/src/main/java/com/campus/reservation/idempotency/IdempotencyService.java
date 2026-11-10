package com.campus.reservation.idempotency;

import com.campus.common.error.BadRequestException;
import com.campus.common.error.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Reusable Idempotency Engine providing atomic persistence, request fingerprinting (SHA-256),
 * cached response replays, and concurrent request protection.
 */
@Slf4j
@Service
public class IdempotencyService {

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
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    public String computeFingerprint(Long userId, String method, String path, Object payload) {
        try {
            String payloadJson = payload != null ? objectMapper.writeValueAsString(payload) : "";
            String raw = userId + ":" + method.toUpperCase() + ":" + path + ":" + payloadJson;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Executes the given action idempotently.
     */
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

        String fingerprint = computeFingerprint(userId, method, path, payload);
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. Inspect existing state outside tx first
        Optional<IdempotencyRecord> existingOpt = repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
        if (existingOpt.isPresent()) {
            IdempotencyRecord record = existingOpt.get();

            // Check if record is expired: if expired, purge it so fresh request can proceed
            if (record.isExpired(now)) {
                requiresNewTx.executeWithoutResult(status -> {
                    repository
                            .findByIdempotencyKeyAndUserId(idempotencyKey, userId)
                            .ifPresent(repository::delete);
                });
            } else {
                if (!record.getRequestFingerprint().equals(fingerprint)) {
                    throw new BadRequestException(
                            "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
                            "Idempotency key has already been used with a different request payload.");
                }

                if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                    throw new ConflictException(
                            "CONCURRENT_IDEMPOTENT_REQUEST",
                            "A request with this idempotency key is currently being processed.");
                }

                if (record.getStatus() == IdempotencyStatus.COMPLETED && record.getResponseBody() != null) {
                    try {
                        T cachedBody = objectMapper.readValue(record.getResponseBody(), responseType);
                        return ResponseEntity.status(record.getResponseStatus())
                                .header("Idempotency-Replayed", "true")
                                .body(cachedBody);
                    } catch (Exception e) {
                        log.error("Failed to deserialize cached idempotency response: {}", e.getMessage());
                    }
                }
            }
        }

        // 2. Atomically reserve the key in an isolated REQUIRES_NEW transaction
        record ReservationResult<T>(boolean newlyCreated, ResponseEntity<T> cachedResponse) {}

        ReservationResult<T> reservationResult;
        try {
            reservationResult = requiresNewTx.execute(status -> {
                Optional<IdempotencyRecord> locked = repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
                if (locked.isPresent()) {
                    IdempotencyRecord existing = locked.get();
                    if (existing.isExpired(now)) {
                        repository.delete(existing);
                    } else {
                        if (!existing.getRequestFingerprint().equals(fingerprint)) {
                            throw new BadRequestException(
                                    "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
                                    "Idempotency key has already been used with a different request payload.");
                        }
                        if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                            throw new ConflictException(
                                    "CONCURRENT_IDEMPOTENT_REQUEST",
                                    "A request with this idempotency key is currently being processed.");
                        }
                        if (existing.getStatus() == IdempotencyStatus.COMPLETED && existing.getResponseBody() != null) {
                            try {
                                T cachedBody = objectMapper.readValue(existing.getResponseBody(), responseType);
                                ResponseEntity<T> replayed = ResponseEntity.status(existing.getResponseStatus())
                                        .header("Idempotency-Replayed", "true")
                                        .body(cachedBody);
                                return new ReservationResult<>(false, replayed);
                            } catch (Exception e) {
                                log.error("Failed to deserialize cached response: {}", e.getMessage());
                            }
                        }
                        // If record was FAILED, delete and allow fresh attempt
                        repository.delete(existing);
                    }
                }

                IdempotencyRecord fresh = new IdempotencyRecord(
                        idempotencyKey, userId, fingerprint, IdempotencyStatus.IN_PROGRESS, now, now.plusHours(24));
                repository.save(fresh);
                return new ReservationResult<>(true, null);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            // Concurrent insert hit unique constraint on (idempotency_key, user_id)
            throw new ConflictException(
                    "CONCURRENT_IDEMPOTENT_REQUEST",
                    "A request with this idempotency key is currently being processed.");
        }

        if (reservationResult != null
                && !reservationResult.newlyCreated()
                && reservationResult.cachedResponse() != null) {
            return reservationResult.cachedResponse();
        }

        // 3. Execute business logic
        ResponseEntity<T> response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            // Mark failed or delete reservation so subsequent corrected attempts can proceed
            requiresNewTx.executeWithoutResult(status -> {
                repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId).ifPresent(rec -> {
                    rec.markFailed();
                    repository.save(rec);
                });
            });
            throw e;
        }

        // 4. Save completed response in REQUIRES_NEW
        final ResponseEntity<T> finalResponse = response;
        requiresNewTx.executeWithoutResult(status -> {
            repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId).ifPresent(rec -> {
                try {
                    String json = objectMapper.writeValueAsString(finalResponse.getBody());
                    rec.markCompleted(finalResponse.getStatusCode().value(), json);
                    repository.save(rec);
                } catch (Exception e) {
                    log.error("Failed to serialize response body for idempotency: {}", e.getMessage());
                    rec.markFailed();
                    repository.save(rec);
                }
            });
        });

        return response;
    }

    /**
     * Autonomous background job running every 2 days to purge expired idempotency records.
     * Fixed delay is set to 172,800,000 ms (48 hours = 2 days), with a configurable property.
     */
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
