package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    private static final Long ASSET_ID = 10L;
    private static final Long USER_1_ID = 101L;
    private static final Long USER_2_ID = 102L;

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

    private WaitlistService waitlistService;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;

    @BeforeEach
    void setUp() {
        waitlistService = new WaitlistService(
                waitlistEntryRepository,
                reservationRepository,
                reservationHoldRepository,
                catalogApi,
                userApi,
                currentUser,
                auditService,
                notificationService,
                clock);
        LocalDateTime now = LocalDateTime.now(clock);
        slotStart = now.plusHours(4);
        slotEnd = slotStart.plusHours(2);
        lenient().when(currentUser.id()).thenReturn(USER_1_ID);
        lenient().when(currentUser.isAdmin()).thenReturn(false);
    }

    @Test
    @DisplayName("joinWaitlist queues a WAITING entry when the requested slot is occupied")
    void joinWaitlist_succeedsWhenSlotOccupied() {
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
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
        verify(auditService).record(eq(USER_1_ID), eq("JOIN_WAITLIST"), eq("WaitlistEntry"), eq(55L), any());
    }

    @Test
    @DisplayName("joinWaitlist rejects with SLOT_ALREADY_FREE when no booking or hold overlaps")
    void joinWaitlist_rejectsWhenSlotIsFree() {
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
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
        AssetSummary asset = new AssetSummary(ASSET_ID, "Scope", "DS1054Z", 1L, 1L, AssetStatus.AVAILABLE);
        when(catalogApi.getAsset(ASSET_ID)).thenReturn(asset);
        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, ReservationService.NO_RESERVATION_ID))
                .thenReturn(true);
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
    @DisplayName("processPromotionsForAsset skips quota-full first waiter and promotes second FIFO waiter")
    void processPromotionsForAsset_skipsQuotaFullAndPromotesSecondFifoWaiter() {
        LocalDateTime now = LocalDateTime.now(clock);
        WaitlistEntry firstQuotaFull = new WaitlistEntry(ASSET_ID, USER_1_ID, slotStart, slotEnd, now.minusMinutes(10));
        ReflectionTestUtils.setField(firstQuotaFull, "id", 1L);
        WaitlistEntry secondEligible = new WaitlistEntry(ASSET_ID, USER_2_ID, slotStart, slotEnd, now.minusMinutes(5));
        ReflectionTestUtils.setField(secondEligible, "id", 2L);

        when(catalogApi.isBookable(ASSET_ID)).thenReturn(true);
        when(waitlistEntryRepository.findByAssetIdAndStatusOrderByCreatedAtAscIdAsc(ASSET_ID, WaitlistStatus.WAITING))
                .thenReturn(List.of(firstQuotaFull, secondEligible));
        when(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, ReservationService.NO_RESERVATION_ID))
                .thenReturn(false)
                .thenReturn(false);
        when(reservationHoldRepository.existsActiveOverlap(
                        eq(ASSET_ID), eq(slotStart), eq(slotEnd), any(), eq(null), any()))
                .thenReturn(false);
        when(userApi.findActiveRole(USER_1_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_2_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        USER_1_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        USER_2_ID, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(1L);
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
                .as(
                        "first waiter at 2-booking limit must be marked SKIPPED_QUOTA_FULL; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(WaitlistStatus.SKIPPED_QUOTA_FULL);
        assertThat(secondEligible.getStatus())
                .as("second FIFO waiter must be marked PROMOTED; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(secondEligible.getPromotedReservationId())
                .as(
                        "promoted waitlist entry must link to created reservation #900; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(900L);
        verify(notificationService).notifyUser(eq(USER_2_ID), eq("Waitlist Spot Confirmed"), any());
    }
}
