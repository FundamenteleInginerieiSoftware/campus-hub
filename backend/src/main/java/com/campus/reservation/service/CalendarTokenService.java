package com.campus.reservation.service;

import com.campus.common.audit.AuditService;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.domain.CalendarFeed;
import com.campus.reservation.repository.CalendarFeedRepository;
import com.campus.reservation.web.CalendarFeedCreatedResponse;
import com.campus.reservation.web.CalendarFeedStatusResponse;
import com.campus.user.api.UserApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, revokes, and resolves per-user secret tokens for the private iCalendar feed.
 *
 * <p>Only the SHA-256 hash of each token is persisted in {@code calendar_feeds}; the raw token is
 * returned once upon creation so a leaked database cannot be turned into working feed URLs.
 */
@Service
@RequiredArgsConstructor
public class CalendarTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final CalendarFeedRepository calendarFeedRepository;
    private final UserApi userApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final Clock clock;

    /** Reports whether the logged-in user has an active calendar subscription link. */
    @Transactional(readOnly = true)
    public CalendarFeedStatusResponse status() {
        Long userId = currentUser.id();
        return new CalendarFeedStatusResponse(
                calendarFeedRepository.findByUserId(userId).isPresent());
    }

    /**
     * Generates a new 256-bit secret calendar link for the logged-in user, replacing any previous
     * link in place so the old URL stops working immediately.
     */
    @Transactional
    public CalendarFeedCreatedResponse create() {
        Long userId = currentUser.id();
        byte[] rawBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(rawBytes);
        String token = BASE64_URL_ENCODER.encodeToString(rawBytes);

        // Store SHA-256 rather than BCrypt: a 256-bit random token cannot be brute-forced, and a
        // deterministic hash lets public feed requests resolve the user in one indexed DB lookup.
        String tokenHash = hash(token);
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        CalendarFeed feed = calendarFeedRepository
                .findByUserId(userId)
                .map(existing -> {
                    existing.replaceToken(tokenHash, now);
                    return existing;
                })
                .orElseGet(() -> new CalendarFeed(userId, tokenHash, now));
        calendarFeedRepository.save(feed);

        auditService.record(userId, "CALENDAR_FEED_CREATED", "USER", userId, "Created private calendar feed link");
        return new CalendarFeedCreatedResponse("/api/v1/calendar/" + token + ".ics");
    }

    /**
     * Revokes the logged-in user's calendar link if one exists. Idempotent when no link is active,
     * writing an audit entry only when an existing link was actually deleted.
     */
    @Transactional
    public void revoke() {
        Long userId = currentUser.id();
        if (calendarFeedRepository.findByUserId(userId).isEmpty()) {
            return;
        }
        calendarFeedRepository.deleteByUserId(userId);
        auditService.record(userId, "CALENDAR_FEED_REVOKED", "USER", userId, "Revoked private calendar feed link");
    }

    /**
     * Resolves a raw feed token from the public {@code /api/v1/calendar/{token}.ics} URL to an
     * active user ID, or empty if the token is malformed, unknown, replaced, revoked, or belongs to
     * a disabled/deleted account.
     */
    @Transactional(readOnly = true)
    public Optional<Long> findActiveUserId(String token) {
        // Validate the exact 43-character base64url shape before hashing or querying the database
        // so malformed or oversized URLs never hit PostgreSQL.
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        String tokenHash = hash(token);
        return calendarFeedRepository
                .findByTokenHash(tokenHash)
                .map(CalendarFeed::getUserId)
                // Re-check UserApi.findActiveRole (Matei's #128): a disabled or deleted account
                // loses access immediately across both JWT endpoints and the public calendar feed.
                .filter(userId -> userApi.findActiveRole(userId).isPresent());
    }

    /** Computes the 64-character lowercase SHA-256 hex digest of a raw feed token. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
