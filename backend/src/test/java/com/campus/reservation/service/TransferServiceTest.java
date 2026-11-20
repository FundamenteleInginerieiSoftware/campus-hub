package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.error.ForbiddenException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.domain.TransferStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.ReservationTransferRepository;
import com.campus.reservation.web.InitiateTransferRequest;
import com.campus.reservation.web.ReservationTransferResponse;
import com.campus.user.api.Role;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Long USER_ID = 42L;
    private static final Long ASSET_ID = 10L;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-02T08:00:00.123456789Z"), ZoneId.of("Europe/Bucharest"));

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationTransferRepository reservationTransferRepository;

    @Mock
    private CatalogApi catalogApi;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserApi userApi;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                reservationRepository,
                reservationTransferRepository,
                catalogApi,
                currentUser,
                auditService,
                notificationService,
                userApi,
                clock);
    }

    @Test
    @DisplayName("Transfer: initiates transfer offer to recipient student and notifies them")
    void initiateTransfer_success() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });

        ReservationTransferResponse response = transferService.initiateTransfer(100L, new InitiateTransferRequest(99L));

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(response.toUserId()).isEqualTo(99L);
        verify(catalogApi).lockForBooking(ASSET_ID);
        verify(notificationService).notifyUser(eq(99L), eq("Reservation Transfer Offer"), any());
        verify(auditService).record(eq(USER_ID), eq("TRANSFER_INITIATED"), eq("ReservationTransfer"), eq(500L), any());
    }

    @Test
    @DisplayName(
            "Transfer: anti-probing: allows initiate even if recipient is at quota (quota enforced at accept, #240 point 3)")
    void initiateTransfer_doesNotProbeRecipientQuota() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.countByReservationIdAndRequestedAtAfter(eq(100L), any()))
                .thenReturn(0L);
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 502L);
            return t;
        });

        ReservationTransferResponse res = transferService.initiateTransfer(100L, new InitiateTransferRequest(99L));
        assertThat(res).isNotNull();
        assertThat(res.id()).isEqualTo(502L);
        assertThat(res.status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("Transfer: rejects initiate if 24-hour transfer offer limit is reached (#240 point 3)")
    void initiateTransfer_rejectsWhenDailyTransferOfferLimitReached() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.countByReservationIdAndRequestedAtAfter(eq(100L), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> transferService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Maximum transfer offers reached for this reservation");
    }

    @Test
    @DisplayName("Transfer: rejects transfer to oneself")
    void initiateTransfer_rejectsSelfTransfer() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> transferService.initiateTransfer(100L, new InitiateTransferRequest(USER_ID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot transfer a reservation to yourself");
    }

    @Test
    @DisplayName("initiateTransfer: rejects transfer of individual items belonging to a bundle (#228 point 1)")
    void initiateTransfer_rejectsBundleItems() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);
        r.assignBundle(UUID.randomUUID());

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> transferService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .as(
                        "bundle security: bundle items cannot be transferred individually; see TransferService.initiateTransfer")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Items part of a bundle cannot be transferred individually");
    }

    @Test
    @DisplayName("initiateTransfer: rejects recipient who is not a student (e.g. ADMIN) (#228 point 2)")
    void initiateTransfer_rejectsNonStudentRecipient() {
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.ADMIN)); // Admin recipient

        assertThatThrownBy(() -> transferService.initiateTransfer(100L, new InitiateTransferRequest(99L)))
                .as("anti-probing: transfers can only be offered to students; see TransferService.initiateTransfer")
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Recipient student not found or inactive");
    }

    @Test
    @DisplayName("initiateTransfer: admin-initiated transfer records reservation owner as fromUserId (#228 point 5)")
    void initiateTransfer_byAdmin_setsFromUserIdToReservationOwner() {
        Long studentOwnerId = 77L;
        Reservation r = new Reservation(
                studentOwnerId,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        when(currentUser.id()).thenReturn(USER_ID); // Admin ID
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(r));
        when(userApi.findActiveRole(99L)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByReservationIdAndStatus(100L, TransferStatus.PENDING))
                .thenReturn(Optional.empty());
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> {
            ReservationTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });

        ReservationTransferResponse response = transferService.initiateTransfer(100L, new InitiateTransferRequest(99L));

        assertThat(response.fromUserId())
                .as(
                        "admin transfer: fromUserId must match student owner, not admin; see TransferService.initiateTransfer")
                .isEqualTo(studentOwnerId);
    }

    @Test
    @DisplayName("Transfer: accepts transfer and atomically changes reservation ownership")
    void acceptTransfer_success() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        recipientId, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(0L);

        ReservationTransferResponse response = transferService.acceptTransfer(500L);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(r.getUserId()).isEqualTo(recipientId); // Ownership transferred!
        verify(reservationRepository).save(r);
        verify(notificationService).notifyUser(eq(USER_ID), eq("Reservation Transfer Accepted"), any());
        verify(auditService)
                .record(eq(recipientId), eq("TRANSFER_ACCEPTED"), eq("ReservationTransfer"), eq(500L), any());
    }

    @Test
    @DisplayName("Transfer: rejects transfer if recipient active quota is exceeded")
    void acceptTransfer_rejectsIfRecipientQuotaExceeded() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));
        when(reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                        recipientId, ReservationStatus.ACTIVE_STATUSES))
                .thenReturn(2L);

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Student cannot have more than 2 active reservations");

        assertThat(r.getUserId()).isEqualTo(USER_ID);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("Transfer: admin accepting transfer bypasses active quota check")
    void acceptTransfer_adminBypassesQuota() {
        Long adminId = 999L;
        Reservation r = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, adminId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(adminId);
        when(currentUser.isAdmin()).thenReturn(true);
        when(userApi.findActiveRole(adminId)).thenReturn(Optional.of(Role.ADMIN));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));

        ReservationTransferResponse response = transferService.acceptTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(r.getUserId()).isEqualTo(adminId);
        verify(reservationRepository, never()).lockUserQuota(adminId);
    }

    @Test
    @DisplayName("Transfer: rejects accept if reservation owner no longer matches transfer sender")
    void acceptTransfer_rejectsIfReservationOwnerChanged() {
        Long recipientId = 99L;
        Reservation r = new Reservation(
                777L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        ReflectionTestUtils.setField(r, "id", 100L);

        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservation owner no longer matches transfer sender");
    }

    @Test
    @DisplayName("Transfer: rejects accept if sender account is no longer active")
    void acceptTransfer_rejectsIfSenderInactive() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.empty());
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Sender account is no longer active");
    }

    @Test
    @DisplayName("Transfer: rejects accept if recipient is disabled while waiting on quota lock (#229)")
    void acceptTransfer_rejectsRecipientDisabledAfterLockingQuota() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        Reservation reservation = new Reservation(
                USER_ID,
                10L,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0));
        ReflectionTestUtils.setField(reservation, "id", 100L);

        when(currentUser.id()).thenReturn(recipientId);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.empty());
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationRepository.findById(100L)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .as("recipient disabled while waiting on lockUserQuota must be rejected with 403 USER_DISABLED; "
                        + "see TransferService.acceptTransfer")
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("code", "USER_DISABLED");

        var order = inOrder(reservationRepository, userApi);
        order.verify(reservationRepository).lockUserQuota(recipientId);
        order.verify(userApi).findActiveRole(recipientId);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Transfer: recipient rejects transfer and notifies sender")
    void rejectTransfer_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = transferService.rejectTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        verify(auditService)
                .record(
                        eq(recipientId),
                        eq("TRANSFER_REJECTED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        contains("Rejected by recipient"));
        verify(notificationService)
                .notifyUser(eq(USER_ID), eq("Reservation Transfer Rejected"), contains("declined your transfer"));
    }

    @Test
    @DisplayName("Transfer: admin can reject transfer on behalf of recipient")
    void rejectTransfer_byAdmin_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(777L);
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = transferService.rejectTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
    }

    @Test
    @DisplayName("Transfer: non-recipient student throws 404 when rejecting transfer")
    void rejectTransfer_nonRecipient_throws404() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(888L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.rejectTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: reject transfer throws 404 when transfer does not exist")
    void rejectTransfer_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.rejectTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: reject transfer throws 409 when status is not PENDING")
    void rejectTransfer_nonPending_throws409() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        transfer.reject(LocalDateTime.of(2026, 11, 2, 12, 0));

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.rejectTransfer(500L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot reject transfer in status REJECTED");
    }

    @Test
    @DisplayName("Transfer: sender cancels pending transfer and notifies recipient")
    void cancelTransfer_senderCancelsAndNotifiesRecipient() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = transferService.cancelTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.CANCELLED);
        verify(reservationTransferRepository).save(transfer);
        verify(notificationService)
                .notifyUser(
                        eq(recipientId),
                        eq("Reservation Transfer Cancelled"),
                        contains("cancelled the transfer offer"));
        verify(auditService)
                .record(
                        eq(USER_ID),
                        eq("TRANSFER_CANCELLED"),
                        eq("ReservationTransfer"),
                        eq(500L),
                        contains("Cancelled by sender"));
    }

    @Test
    @DisplayName("Transfer: admin can cancel outgoing transfer offer")
    void cancelTransfer_byAdmin_success() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(777L);
        when(currentUser.isAdmin()).thenReturn(true);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(reservationTransferRepository.save(any(ReservationTransfer.class))).thenAnswer(inv -> inv.getArgument(0));

        ReservationTransferResponse response = transferService.cancelTransfer(500L);

        assertThat(response.status()).isEqualTo(TransferStatus.CANCELLED);
    }

    @Test
    @DisplayName("Transfer: non-sender student throws 404 when cancelling transfer")
    void cancelTransfer_nonSender_throws404() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(888L);
        when(currentUser.isAdmin()).thenReturn(false);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.cancelTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: cancel transfer throws 404 when transfer does not exist")
    void cancelTransfer_notFound_throws404() {
        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.cancelTransfer(500L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Transfer request not found");
    }

    @Test
    @DisplayName("Transfer: cancel transfer throws 409 when status is not PENDING")
    void cancelTransfer_nonPending_throws409() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);
        transfer.cancel(LocalDateTime.of(2026, 11, 2, 12, 0));

        when(currentUser.id()).thenReturn(USER_ID);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> transferService.cancelTransfer(500L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot cancel transfer in status CANCELLED");
    }

    @Test
    @DisplayName("Transfer: getMyPendingTransfers lists transfers directed to current user")
    void getMyPendingTransfers_returnsList() {
        Long recipientId = USER_ID;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, 2L, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByToUserIdAndStatusOrderByRequestedAtDesc(
                        eq(recipientId), eq(TransferStatus.PENDING)))
                .thenReturn(List.of(transfer));

        List<ReservationTransferResponse> list = transferService.getMyPendingTransfers();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(500L);
    }

    @Test
    @DisplayName("Transfer: acceptTransfer throws 404 TRANSFER_NOT_FOUND when transfer does not exist")
    void acceptTransfer_transferNotFound_throws404() {
        when(currentUser.id()).thenReturn(99L);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .as(
                        "existence: accepting a missing transfer must throw TRANSFER_NOT_FOUND; see TransferService.acceptTransfer")
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "TRANSFER_NOT_FOUND");
    }

    @Test
    @DisplayName("Transfer: acceptTransfer throws 404 RESERVATION_NOT_FOUND when underlying reservation is missing")
    void acceptTransfer_reservationNotFound_throws404() {
        Long recipientId = 99L;
        ReservationTransfer transfer =
                new ReservationTransfer(100L, USER_ID, recipientId, LocalDateTime.of(2026, 11, 2, 11, 0));
        ReflectionTestUtils.setField(transfer, "id", 500L);

        when(currentUser.id()).thenReturn(recipientId);
        when(reservationTransferRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(transfer));
        when(userApi.findActiveRole(recipientId)).thenReturn(Optional.of(Role.STUDENT));
        when(userApi.findActiveRole(USER_ID)).thenReturn(Optional.of(Role.STUDENT));
        when(reservationRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.acceptTransfer(500L))
                .as(
                        "existence: accepting a transfer whose reservation disappeared must throw RESERVATION_NOT_FOUND; see TransferService.acceptTransfer")
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "RESERVATION_NOT_FOUND");
    }
}
