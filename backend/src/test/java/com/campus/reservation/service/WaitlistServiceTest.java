package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.WaitlistEntryResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    private static final Long ASSET_ID = 10L;
    private static final Long STUDENT_1 = 101L;
    private static final Long STUDENT_2 = 102L;

    @Mock
    private WaitlistEntryRepository waitlistEntryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    private Clock clock;
    private WaitlistService waitlistService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        waitlistService = new WaitlistService(
                waitlistEntryRepository,
                reservationRepository,
                reservationService,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                clock);
    }

    @Test
    @DisplayName(
            "joinWaitlist delegates to ReservationService.validateSlotRules and rejects invalid weekend/unaligned slots")
    void joinEnforcesSharedBookingRules() {
        LocalDateTime satStart = LocalDateTime.of(2026, 11, 7, 3, 17);
        LocalDateTime satEnd = LocalDateTime.of(2026, 11, 7, 4, 0);
        when(currentUser.id()).thenReturn(STUDENT_1);
        doThrow(new BusinessRuleException("SLOT_NOT_ALIGNED", "Not aligned"))
                .when(reservationService)
                .validateSlotRules(eq(satStart), eq(satEnd), any());

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new CreateReservationRequest(ASSET_ID, satStart, satEnd)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "SLOT_NOT_ALIGNED");
    }

    @Test
    @DisplayName("joinWaitlist rejects when requested slot is already free (SLOT_ALREADY_FREE)")
    void joinRejectsWhenSlotAlreadyFree() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        when(currentUser.id()).thenReturn(STUDENT_1);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new CreateReservationRequest(ASSET_ID, start, end)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "SLOT_ALREADY_FREE");
    }

    @Test
    @DisplayName("processPromotionsForAsset skips FIFO candidate whose quota is full and promotes the next student")
    void promotesNextEligibleCandidateInFifoOrder() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        WaitlistEntry firstQuotaFull = new WaitlistEntry(STUDENT_1, ASSET_ID, start, end, now.minusMinutes(20));
        ReflectionTestUtils.setField(firstQuotaFull, "id", 1L);
        WaitlistEntry secondEligible = new WaitlistEntry(STUDENT_2, ASSET_ID, start, end, now.minusMinutes(10));
        ReflectionTestUtils.setField(secondEligible, "id", 2L);

        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(waitlistEntryRepository.findWaitingByAssetOrderByFifo(ASSET_ID, now))
                .thenReturn(List.of(firstQuotaFull, secondEligible));
        when(reservationRepository.existsOverlap(ASSET_ID, start, end, ReservationStatus.ACTIVE_STATUSES, -1L))
                .thenReturn(false);

        when(reservationRepository.countByUserIdAndStatusIn(STUDENT_1, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);
        when(reservationRepository.countByUserIdAndStatusIn(STUDENT_2, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 555L);
            return r;
        });

        List<WaitlistEntryResponse> promoted = waitlistService.processPromotionsForAsset(ASSET_ID);

        assertThat(firstQuotaFull.getStatus()).isEqualTo(WaitlistStatus.SKIPPED_QUOTA_FULL);
        assertThat(secondEligible.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(secondEligible.getPromotedReservationId()).isEqualTo(555L);
        assertThat(promoted).hasSize(1);
        assertThat(promoted.get(0).userId()).isEqualTo(STUDENT_2);

        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(notificationService).notifyUser(eq(STUDENT_2), any(), any());
    }

    @Test
    @DisplayName("cancelWaitlistEntry enforces IDOR protection by throwing 404 for another student's entry")
    void cancelEnforcesIdorProtection() {
        WaitlistEntry entry = new WaitlistEntry(
                STUDENT_1,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.now(clock));
        when(currentUser.id()).thenReturn(STUDENT_2);
        when(currentUser.isAdmin()).thenReturn(false);
        when(waitlistEntryRepository.findById(99L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> waitlistService.cancelWaitlistEntry(99L))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "WAITLIST_NOT_FOUND");
    }
}
