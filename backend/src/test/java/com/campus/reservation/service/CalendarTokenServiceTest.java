package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campus.common.audit.AuditService;
import com.campus.common.security.CurrentUser;
import com.campus.reservation.domain.CalendarFeed;
import com.campus.reservation.repository.CalendarFeedRepository;
import com.campus.reservation.web.CalendarFeedCreatedResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CalendarTokenServiceTest {

    private static final Long USER_ID = 42L;
    private static final String PATH_PREFIX = "/api/v1/calendar/";
    private static final String PATH_SUFFIX = ".ics";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private CalendarFeedRepository calendarFeedRepository;

    @Mock
    private UserApi userApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    private CalendarTokenService service;

    @BeforeEach
    void setUp() {
        service = new CalendarTokenService(calendarFeedRepository, userApi, currentUser, auditService, clock);
    }

    private static String extractToken(CalendarFeedCreatedResponse response) {
        String path = response.path();
        return path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
    }

    @Test
    void createReturnsAPathWithA43CharToken() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CalendarFeedCreatedResponse response = service.create();

        assertThat(response.path())
                .as(
                        "created calendar link path must contain a 43-char base64url token; see CalendarTokenService.create")
                .matches("/api/v1/calendar/[A-Za-z0-9_-]{43}\\.ics");
    }

    @Test
    void createSavesOnlyTheHash() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CalendarFeedCreatedResponse response = service.create();
        String token = extractToken(response);

        ArgumentCaptor<CalendarFeed> captor = ArgumentCaptor.forClass(CalendarFeed.class);
        verify(calendarFeedRepository).save(captor.capture());
        CalendarFeed saved = captor.getValue();

        assertThat(saved.getTokenHash())
                .as("persisted tokenHash must equal SHA-256 hex of the returned token; see CalendarTokenService.create")
                .isEqualTo(CalendarTokenService.hash(token))
                .matches("[0-9a-f]{64}")
                .doesNotContain(token);
        assertThat(saved.getUserId())
                .as("saved CalendarFeed must belong to currentUser.id(); see CalendarTokenService.create")
                .isEqualTo(USER_ID);
    }

    @Test
    void secondCreateReplacesTheFirst() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));

        AtomicReference<CalendarFeed> stored = new AtomicReference<>();
        when(calendarFeedRepository.findByUserId(USER_ID)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(calendarFeedRepository.save(any(CalendarFeed.class))).thenAnswer(inv -> {
            CalendarFeed feed = inv.getArgument(0);
            stored.set(feed);
            return feed;
        });
        when(calendarFeedRepository.findByTokenHash(any())).thenAnswer(inv -> {
            String requestedHash = inv.getArgument(0);
            CalendarFeed feed = stored.get();
            return feed != null && feed.getTokenHash().equals(requestedHash) ? Optional.of(feed) : Optional.empty();
        });

        String firstToken = extractToken(service.create());
        String secondToken = extractToken(service.create());

        assertThat(service.findActiveUserId(firstToken))
                .as("first token must stop working once replaced by second create; see CalendarTokenService.create")
                .isEmpty();
        assertThat(service.findActiveUserId(secondToken))
                .as("second token must resolve to the active user; see CalendarTokenService.findActiveUserId")
                .contains(USER_ID);
    }

    @Test
    void twoCreatesGiveDifferentTokens() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        String first = extractToken(service.create());
        String second = extractToken(service.create());

        assertThat(second)
                .as("each create() call must generate a fresh random token; see CalendarTokenService.create")
                .isNotEqualTo(first);
    }

    @Test
    void revokeDeletesTheRow() {
        when(currentUser.id()).thenReturn(USER_ID);
        CalendarFeed existing = new CalendarFeed(USER_ID, "a".repeat(64), LocalDateTime.now(clock));
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        service.revoke();

        verify(calendarFeedRepository).deleteByUserId(USER_ID);
    }

    @Test
    void revokeWithoutALinkDoesNothing() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        service.revoke();

        verify(calendarFeedRepository, never()).deleteByUserId(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void statusReflectsTheRow() {
        when(currentUser.id()).thenReturn(USER_ID);
        AtomicReference<CalendarFeed> stored = new AtomicReference<>();
        when(calendarFeedRepository.findByUserId(USER_ID)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(calendarFeedRepository.save(any(CalendarFeed.class))).thenAnswer(inv -> {
            CalendarFeed feed = inv.getArgument(0);
            stored.set(feed);
            return feed;
        });

        assertThat(service.status().enabled())
                .as("status must be false before a calendar link is created; see CalendarTokenService.status")
                .isFalse();

        service.create();

        assertThat(service.status().enabled())
                .as("status must be true once a calendar link row exists; see CalendarTokenService.status")
                .isTrue();
    }

    @Test
    void findsActiveUserByToken() {
        String token = "A".repeat(43);
        String hash = CalendarTokenService.hash(token);
        CalendarFeed feed = new CalendarFeed(USER_ID, hash, LocalDateTime.now(clock));
        when(calendarFeedRepository.findByTokenHash(hash)).thenReturn(Optional.of(feed));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));

        Optional<Long> resolved = service.findActiveUserId(token);

        assertThat(resolved)
                .as("valid token for an active user must resolve to userId; see CalendarTokenService.findActiveUserId")
                .contains(USER_ID);
    }

    @Test
    void disabledUserIsEmpty() {
        String token = "B".repeat(43);
        String hash = CalendarTokenService.hash(token);
        CalendarFeed feed = new CalendarFeed(USER_ID, hash, LocalDateTime.now(clock));
        when(calendarFeedRepository.findByTokenHash(hash)).thenReturn(Optional.of(feed));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.empty());

        Optional<Long> resolved = service.findActiveUserId(token);

        assertThat(resolved)
                .as(
                        "disabled or deleted account must lose feed access immediately; see CalendarTokenService.findActiveUserId")
                .isEmpty();
    }

    @Test
    void unknownTokenIsEmpty() {
        String token = "C".repeat(43);
        String hash = CalendarTokenService.hash(token);
        when(calendarFeedRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

        Optional<Long> resolved = service.findActiveUserId(token);

        assertThat(resolved)
                .as("unknown token hash must return empty; see CalendarTokenService.findActiveUserId")
                .isEmpty();
        verifyNoInteractions(userApi);
    }

    @Test
    void malformedTokenNeverReachesTheDatabase() {
        String[] malformedTokens = {
            null, "", "a".repeat(42), "a".repeat(44), "a".repeat(42) + "/", "a".repeat(42) + "+", "a".repeat(42) + "."
        };

        for (String malformed : malformedTokens) {
            assertThat(service.findActiveUserId(malformed))
                    .as(
                            "malformed token '%s' must be rejected before querying the DB; see CalendarTokenService.findActiveUserId",
                            malformed)
                    .isEmpty();
        }
        verifyNoInteractions(calendarFeedRepository);
        verifyNoInteractions(userApi);
    }

    @Test
    void createWritesAudit() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        service.create();

        verify(auditService, times(1))
                .record(USER_ID, "CALENDAR_FEED_CREATED", "USER", USER_ID, "Created private calendar feed link");
    }

    @Test
    void revokeWritesAudit() {
        when(currentUser.id()).thenReturn(USER_ID);
        CalendarFeed existing = new CalendarFeed(USER_ID, "d".repeat(64), LocalDateTime.now(clock));
        when(calendarFeedRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        service.revoke();

        verify(auditService, times(1))
                .record(USER_ID, "CALENDAR_FEED_REVOKED", "USER", USER_ID, "Revoked private calendar feed link");
    }
}
