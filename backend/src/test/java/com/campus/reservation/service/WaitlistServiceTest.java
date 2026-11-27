package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.JoinWaitlistRequest;
import com.campus.reservation.web.WaitlistEntryResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    private static final Long ASSET_ID = 10L;
    private static final Long USER_1_ID = 101L;
    private static final Long USER_2_ID = 102L;
    private static final Long USER_3_ID = 103L;

    // 2026-11-02 10:00 Europe/Bucharest (Monday)
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private WaitlistEntryRepository waitlistEntryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationHoldRepository reservationHoldRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private UserApi userApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private WaitlistService waitlistService;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;

    @BeforeEach
    void setUp() {
        TransactionStatus txStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        waitlistService = new WaitlistService(
                waitlistEntryRepository,
                reservationRepository,
                reservationHoldRepository,
                catalogApi,
                userApi,
                currentUser,
                auditService,
                notificationService,
                clock,
                transactionManager);
        LocalDateTime now = LocalDateTime.now(clock);
        slotStart = now.plusHours(4);
        slotEnd = slotStart.plusHours(2);
        lenient().when(currentUser.id()).thenReturn(USER_1_ID);
        lenient().when(currentUser.isAdmin()).thenReturn(false);
        lenient().when(userApi.findActiveRole(USER_1_ID)).thenReturn(Optional.of(Role.STUDENT));
        lenient()
                .when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE));
    }

    @Test
    @DisplayName("joinWaitlist queues a WAITING entry when the requested slot is occupied")
    void joinWaitlist_succeedsWhenSlotOccupied() {
        when(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, ReservationService.NO_RESERVATION_ID))
                .thenReturn(true);
        when(waitlistEntryRepository.save(any(WaitlistEntry.class))).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 55L);
            return e;
        });

        WaitlistEntryResponse res = waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd));

        assertThat(res.id())
                .as("joinWaitlist must persist and return entry id; see WaitlistService.joinWaitlist")
                .isEqualTo(55L);
        assertThat(res.status())
                .as("new waitlist entry must start in WAITING status; see WaitlistService.joinWaitlist")
                .isEqualTo(WaitlistStatus.WAITING);
        InOrder lockOrder = inOrder(catalogApi, reservationRepository);
        lockOrder.verify(catalogApi).lockForBooking(ASSET_ID);
        lockOrder.verify(reservationRepository).lockUserQuota(USER_1_ID);
        verify(auditService).record(eq(USER_1_ID), eq("JOIN_WAITLIST"), eq("WaitlistEntry"), eq(55L), any());
    }

    @Test
    @DisplayName("joinWaitlist hides RETIRED asset from students with 404 ASSET_NOT_FOUND (#278, #362)")
    void joinWaitlist_retiredAssetThrowsNotFoundForStudent() {
        when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.RETIRED));
        when(currentUser.isAdmin()).thenReturn(false);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("retired asset must throw 404 ASSET_NOT_FOUND for students; see WaitlistService.joinWaitlist")
                .isInstanceOf(NotFoundException.class)
                .extracting(ex -> ((NotFoundException) ex).getCode())
                .isEqualTo("ASSET_NOT_FOUND");
    }

    @Test
    @DisplayName(
            "joinWaitlist returns 409 ASSET_NOT_BOOKABLE for admins on RETIRED asset or students on UNDER_MAINTENANCE")
    void joinWaitlist_nonBookableAssetReturnsConflict() {
        when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.UNDER_MAINTENANCE));

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("UNDER_MAINTENANCE asset must throw 409 ASSET_NOT_BOOKABLE; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("ASSET_NOT_BOOKABLE");

        when(catalogApi.getAsset(ASSET_ID))
                .thenReturn(new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.RETIRED));
        when(currentUser.isAdmin()).thenReturn(true);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("RETIRED asset for admin must throw 409 ASSET_NOT_BOOKABLE; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("ASSET_NOT_BOOKABLE");
    }

    @Test
    @DisplayName("joinWaitlist rejects with SLOT_ALREADY_FREE when no booking or hold overlaps")
    void joinWaitlist_rejectsWhenSlotIsFree() {
        when(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, ReservationService.NO_RESERVATION_ID))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(slotStart), eq(slotEnd), any(), eq(null), eq(USER_1_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("free slot must reject waitlist join with SLOT_ALREADY_FREE; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("SLOT_ALREADY_FREE");
    }

    @Test
    @DisplayName("joinWaitlist rejects duplicate WAITING entry with WAITLIST_ALREADY_JOINED")
    void joinWaitlist_rejectsDuplicateWaitingEntry() {
        when(waitlistEntryRepository.existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
                        ASSET_ID, USER_1_ID, slotStart, slotEnd, WaitlistStatus.WAITING))
                .thenReturn(true);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("duplicate waiting entry must throw WAITLIST_ALREADY_JOINED; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("WAITLIST_ALREADY_JOINED");
    }

    @Test
    @DisplayName("joinWaitlist checks WAITLIST_ALREADY_JOINED before WAITLIST_LIMIT_REACHED when at cap")
    void joinWaitlist_alreadyJoinedAtCapReturnsAlreadyJoinedInsteadOfLimitReached() {
        when(waitlistEntryRepository.existsByAssetIdAndUserIdAndStartTimeAndEndTimeAndStatus(
                        ASSET_ID, USER_1_ID, slotStart, slotEnd, WaitlistStatus.WAITING))
                .thenReturn(true);
        lenient()
                .when(waitlistEntryRepository.countByUserIdAndStatus(USER_1_ID, WaitlistStatus.WAITING))
                .thenReturn((long) WaitlistService.MAX_WAITING_ENTRIES);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("duplicate join while at 5-entry cap must report WAITLIST_ALREADY_JOINED; "
                        + "see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("WAITLIST_ALREADY_JOINED");
    }

    @Test
    @DisplayName("joinWaitlist rejects when caller already owns an active reservation for that slot")
    void joinWaitlist_rejectsOwnReservationOverlap() {
        when(reservationRepository.existsUserOverlapOnAsset(ASSET_ID, USER_1_ID, slotStart, slotEnd))
                .thenReturn(true);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as(
                        "caller owning overlapping booking must get OWN_RESERVATION_CONFLICT; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("OWN_RESERVATION_CONFLICT");
    }

    @Test
    @DisplayName("joinWaitlist rejects when student reaches active waitlist entry cap (5)")
    void joinWaitlist_rejectsWhenWaitlistLimitReached() {
        when(waitlistEntryRepository.countByUserIdAndStatus(USER_1_ID, WaitlistStatus.WAITING))
                .thenReturn((long) WaitlistService.MAX_WAITING_ENTRIES);

        assertThatThrownBy(() -> waitlistService.joinWaitlist(new JoinWaitlistRequest(ASSET_ID, slotStart, slotEnd)))
                .as("student at waitlist limit (5) must get WAITLIST_LIMIT_REACHED; see WaitlistService.joinWaitlist")
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("WAITLIST_LIMIT_REACHED");
    }

    @Test
    @DisplayName("cancelWaitingEntriesForUser cancels disabled user's entries without locking userQuota")
    void cancelWaitingEntriesForUser_cancelsEntriesWithoutLockingUserQuota() {
        LocalDateTime now = LocalDateTime.now(clock);
        when(userApi.findActiveRole(USER_1_ID)).thenReturn(Optional.empty());
        WaitlistEntry entry = new WaitlistEntry(ASSET_ID, USER_1_ID, slotStart, slotEnd, now.minusMinutes(10));
        ReflectionTestUtils.setField(entry, "id", 72L);
        when(waitlistEntryRepository.findByUserIdAndStatusForUpdate(USER_1_ID, WaitlistStatus.WAITING))
                .thenReturn(List.of(entry));

        int cancelled = waitlistService.cancelWaitingEntriesForUser(USER_1_ID);

        assertThat(cancelled)
                .as("cancelWaitingEntriesForUser must return cancelled count; "
                        + "see WaitlistService.cancelWaitingEntriesForUser")
                .isEqualTo(1);
        assertThat(entry.getStatus())
                .as("disabled user's waiting entry must transition to CANCELLED; "
                        + "see WaitlistService.cancelWaitingEntriesForUser")
                .isEqualTo(WaitlistStatus.CANCELLED);
        verify(reservationRepository, never()).lockUserQuota(any());
    }

    @Test
    @DisplayName("cancelWaitingEntriesForUser skips cancellation if user was already re-enabled (#349)")
    void cancelWaitingEntriesForUser_skipsWhenUserReEnabled() {
        when(userApi.findActiveRole(USER_1_ID)).thenReturn(Optional.of(Role.STUDENT));

        int cancelled = waitlistService.cancelWaitingEntriesForUser(USER_1_ID);

        assertThat(cancelled)
                .as(
                        "re-enabled user's waitlist entries must not be cancelled; see WaitlistService.cancelWaitingEntriesForUser")
                .isZero();
        verify(waitlistEntryRepository, never()).findByUserIdAndStatusForUpdate(any(), any());
    }

    @Test
    @DisplayName("cancelWaitlistEntry hides other students' entries with 404")
    void cancelWaitlistEntry_hidesForeignEntryWithNotFound() {
        WaitlistEntry foreign = new WaitlistEntry(ASSET_ID, USER_2_ID, slotStart, slotEnd, LocalDateTime.now(clock));
        ReflectionTestUtils.setField(foreign, "id", 70L);
        when(waitlistEntryRepository.findByIdForUpdate(70L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> waitlistService.cancelWaitlistEntry(70L))
                .as("non-owner cancel must throw 404 WAITLIST_ENTRY_NOT_FOUND; see WaitlistService.cancelWaitlistEntry")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("cancelWaitlistEntry rejects non-WAITING entry with INVALID_WAITLIST_STATUS_TRANSITION")
    void cancelWaitlistEntry_rejectsNonWaitingEntry() {
        LocalDateTime now = LocalDateTime.now(clock);
        WaitlistEntry promoted = new WaitlistEntry(ASSET_ID, USER_1_ID, slotStart, slotEnd, now.minusMinutes(10));
        ReflectionTestUtils.setField(promoted, "id", 71L);
        promoted.markPromoted(888L, now);
        when(waitlistEntryRepository.findByIdForUpdate(71L)).thenReturn(Optional.of(promoted));

        assertThatThrownBy(() -> waitlistService.cancelWaitlistEntry(71L))
                .as(
                        "cancelling non-WAITING entry must throw INVALID_WAITLIST_STATUS_TRANSITION; see WaitlistEntry.cancel")
                .isInstanceOf(InvalidStateTransitionException.class)
                .extracting(ex -> ((InvalidStateTransitionException) ex).getCode())
                .isEqualTo("INVALID_WAITLIST_STATUS_TRANSITION");
    }

    @Test
    @DisplayName(
            "processPromotionsForAsset skips quota-full and disabled waiters, promotes next FIFO waiter and confirms overlapping hold")
    void processPromotionsForAsset_skipsQuotaFullDisabledAndPromotesWithHoldConfirmation() {
        LocalDateTime now = LocalDateTime.now(clock);
        WaitlistEntry firstQuotaFull = new WaitlistEntry(ASSET_ID, USER_1_ID, slotStart, slotEnd, now.minusMinutes(15));
        ReflectionTestUtils.setField(firstQuotaFull, "id", 1L);
        WaitlistEntry secondDisabled = new WaitlistEntry(ASSET_ID, USER_3_ID, slotStart, slotEnd, now.minusMinutes(10));
        ReflectionTestUtils.setField(secondDisabled, "id", 2L);
        WaitlistEntry thirdEligible = new WaitlistEntry(ASSET_ID, USER_2_ID, slotStart, slotEnd, now.minusMinutes(5));
        ReflectionTestUtils.setField(thirdEligible, "id", 3L);

        // Partial overlapping hold (first hour of the 2-hour promoted slot) must also be locked & confirmed (#349,
        // #362).
        ReservationHold ownedHold =
                new ReservationHold(ASSET_ID, USER_2_ID, slotStart, slotStart.plusHours(1), now.plusMinutes(1), now);

        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(waitlistEntryRepository.findByAssetIdAndStatusForUpdate(ASSET_ID, WaitlistStatus.WAITING))
                .thenReturn(List.of(firstQuotaFull, secondDisabled, thirdEligible));
        when(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, ReservationService.NO_RESERVATION_ID))
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(slotStart), eq(slotEnd), any(), eq(null), any()))
                .thenReturn(false);
        when(userApi.findActiveRole(USER_1_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_3_ID)).thenReturn(Optional.empty());
        when(userApi.findActiveRole(USER_2_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        USER_1_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        USER_2_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
        when(reservationHoldRepository.findActiveOverlappingForUserAndAssetsForUpdate(
                        eq(USER_2_ID), eq(List.of(ASSET_ID)), eq(slotStart), eq(slotEnd), eq(now)))
                .thenReturn(List.of(ownedHold));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", 900L);
            return r;
        });

        int promoted = waitlistService.processPromotionsForAsset(ASSET_ID);

        assertThat(promoted)
                .as("exactly 1 eligible waiter must be promoted; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(1);
        assertThat(firstQuotaFull.getStatus())
                .as("first waiter at 2-booking limit must be marked SKIPPED_QUOTA_FULL")
                .isEqualTo(WaitlistStatus.SKIPPED_QUOTA_FULL);
        assertThat(secondDisabled.getStatus())
                .as("disabled waiter must be marked EXPIRED during promotion")
                .isEqualTo(WaitlistStatus.EXPIRED);
        assertThat(thirdEligible.getStatus())
                .as("third eligible FIFO waiter must be marked PROMOTED")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(ownedHold.getStatus())
                .as("promoted waiter's overlapping active hold must transition to CONFIRMED")
                .isEqualTo(HoldStatus.CONFIRMED);
        verify(auditService).record(isNull(), eq("PROMOTE_WAITLIST"), eq("Reservation"), eq(900L), any());
        verify(notificationService).notifyUser(eq(USER_2_ID), eq("Waitlist Spot Confirmed"), any());
    }
}
