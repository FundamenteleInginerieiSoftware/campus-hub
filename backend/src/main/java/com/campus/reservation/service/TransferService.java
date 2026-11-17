package com.campus.reservation.service;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing peer-to-peer reservation transfers (initiate, accept, reject, cancel, and cascade cancellation).
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final ReservationRepository reservationRepository;
    private final ReservationTransferRepository reservationTransferRepository;
    private final CatalogApi catalogApi;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserApi userApi;
    private final Clock clock;

    /**
     * Initiates a transfer request offering a confirmed reservation to another student.
     */
    @Transactional
    public ReservationTransferResponse initiateTransfer(Long reservationId, InitiateTransferRequest req) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        Reservation reservation =
                ReservationServiceSupport.loadOwnedOrAdminForUpdate(reservationRepository, currentUser, reservationId);

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(
                    "INVALID_RESERVATION_STATUS_TRANSITION",
                    "Only CONFIRMED reservations can be transferred; current status is " + reservation.getStatus());
        }

        if (!now.isBefore(reservation.getStartTime())) {
            throw new ConflictException(
                    "TRANSFER_NOT_ALLOWED", "Cannot transfer a reservation that has already started or elapsed");
        }

        // Items in a multi-asset bundle cannot be transferred individually (#228 point 1)
        if (reservation.getBundleId() != null) {
            throw new ConflictException(
                    "BUNDLE_TRANSFER_NOT_ALLOWED", "Items part of a bundle cannot be transferred individually");
        }

        Long recipientId = req.recipientUserId();
        Long fromUserId = reservation.getUserId();
        if (recipientId.equals(fromUserId)) {
            throw new ConflictException("CANNOT_TRANSFER_TO_SELF", "Cannot transfer a reservation to yourself");
        }

        // Verify recipient exists, is active, and is a STUDENT (#228 point 2)
        Optional<Role> activeRole = userApi.findActiveRole(recipientId);
        if (activeRole.isEmpty() || activeRole.get() != Role.STUDENT) {
            throw new NotFoundException("USER_NOT_FOUND", "Recipient student not found or inactive: " + recipientId);
        }

        // Row lock serializes simultaneous transfer requests on the same reservation,
        // so concurrent requests cleanly receive 409 TRANSFER_ALREADY_PENDING instead of a DB unique constraint
        // violation (#228 point 3)
        catalogApi.lockForBooking(reservation.getAssetId());

        if (reservationTransferRepository
                .findByReservationIdAndStatus(reservationId, TransferStatus.PENDING)
                .isPresent()) {
            throw new ConflictException(
                    "TRANSFER_ALREADY_PENDING", "A transfer offer is already pending for this reservation");
        }

        // Anti-spam guard: limit to at most 3 transfer offers per reservation in a 24-hour window (#240 point 3)
        LocalDateTime dayAgo = now.minusHours(24);
        if (reservationTransferRepository.countByReservationIdAndRequestedAtAfter(reservationId, dayAgo) >= 3) {
            throw new ConflictException(
                    "TRANSFER_LIMIT_REACHED",
                    "Maximum transfer offers reached for this reservation in the last 24 hours");
        }

        // fromUserId is always the reservation owner (fixes admin transfer fromUserId bug, #228 point 5)
        ReservationTransfer transfer = new ReservationTransfer(reservationId, fromUserId, recipientId, now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_INITIATED",
                "ReservationTransfer",
                saved.getId(),
                "Offered reservation #" + reservationId + " to user #" + recipientId);

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
                recipientId,
                "Reservation Transfer Offer",
                "User #" + fromUserId + " has offered to transfer reservation #" + reservationId + " to you.");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Retrieves all incoming pending transfer requests for the authenticated student.
     */
    public List<ReservationTransferResponse> getMyPendingTransfers() {
        Long userId = currentUser.id();
        return reservationTransferRepository
                .findByToUserIdAndStatusOrderByRequestedAtDesc(userId, TransferStatus.PENDING)
                .stream()
                .map(ReservationTransferResponse::from)
                .toList();
    }

    /**
     * Accepts a pending transfer offer and atomically moves reservation ownership to the recipient.
     */
    @Transactional
    public ReservationTransferResponse acceptTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getToUserId().equals(userId)) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot accept transfer in status " + transfer.getStatus());
        }

        if (userApi.findActiveRole(userId).isEmpty()) {
            throw new NotFoundException("USER_NOT_FOUND", "Recipient user not found or inactive: " + userId);
        }

        if (userApi.findActiveRole(transfer.getFromUserId()).isEmpty()) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Sender account is no longer active");
        }

        Reservation reservation = reservationRepository
                .findById(transfer.getReservationId())
                .orElseThrow(() -> new NotFoundException("RESERVATION_NOT_FOUND", "Reservation not found"));

        if (reservation.getStatus() != ReservationStatus.CONFIRMED || !now.isBefore(reservation.getStartTime())) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Reservation is no longer valid for transfer");
        }

        // Ensure reservation owner still matches transfer sender (prevents double transfer or stale ownership)
        if (!reservation.getUserId().equals(transfer.getFromUserId())) {
            throw new ConflictException("TRANSFER_NOT_ALLOWED", "Reservation owner no longer matches transfer sender");
        }

        if (!currentUser.isAdmin()) {
            reservationRepository.lockUserQuota(userId);
            if (reservationRepository.countBookingSessionsByUserIdAndStatusIn(userId, ReservationStatus.ACTIVE_STATUSES)
                    >= 2) {
                throw new ConflictException(
                        "RESERVATION_LIMIT_REACHED", "Student cannot have more than 2 active reservations");
            }
        }

        transfer.accept(now);
        reservation.transferOwnership(userId);

        reservationTransferRepository.save(transfer);
        reservationRepository.save(reservation);

        auditService.record(
                userId,
                "TRANSFER_ACCEPTED",
                "ReservationTransfer",
                transfer.getId(),
                "Ownership of reservation #"
                        + reservation.getId()
                        + " transferred from user #"
                        + transfer.getFromUserId()
                        + " to #"
                        + userId);

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
                transfer.getFromUserId(),
                "Reservation Transfer Accepted",
                "User #" + userId + " accepted your transfer of reservation #" + reservation.getId() + ".");

        return ReservationTransferResponse.from(transfer);
    }

    /**
     * Rejects an incoming pending reservation transfer request.
     */
    @Transactional
    public ReservationTransferResponse rejectTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getToUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot reject transfer in status " + transfer.getStatus());
        }

        transfer.reject(now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_REJECTED",
                "ReservationTransfer",
                transfer.getId(),
                "Rejected by recipient user #" + userId);

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
                transfer.getFromUserId(),
                "Reservation Transfer Rejected",
                "User #" + userId + " declined your transfer of reservation #" + transfer.getReservationId() + ".");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Cancels an outgoing pending reservation transfer request by the original sender.
     */
    @Transactional
    public ReservationTransferResponse cancelTransfer(Long transferId) {
        Long userId = currentUser.id();
        LocalDateTime now = ReservationServiceSupport.truncatedNow(clock);

        ReservationTransfer transfer = reservationTransferRepository
                .findByIdForUpdate(transferId)
                .orElseThrow(() -> new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found"));

        if (!transfer.getFromUserId().equals(userId) && !currentUser.isAdmin()) {
            throw new NotFoundException("TRANSFER_NOT_FOUND", "Transfer request not found");
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "INVALID_TRANSFER_STATUS_TRANSITION", "Cannot cancel transfer in status " + transfer.getStatus());
        }

        transfer.cancel(now);
        ReservationTransfer saved = reservationTransferRepository.save(transfer);

        auditService.record(
                userId,
                "TRANSFER_CANCELLED",
                "ReservationTransfer",
                transfer.getId(),
                "Cancelled by sender user #" + userId);

        ReservationServiceSupport.notifyUserAfterCommit(
                notificationService,
                transfer.getToUserId(),
                "Reservation Transfer Cancelled",
                "User #" + userId + " cancelled the transfer offer for reservation #" + transfer.getReservationId()
                        + ".");

        return ReservationTransferResponse.from(saved);
    }

    /**
     * Auto-cancels any pending transfer offer when a reservation becomes invalid
     * (cancelled, force-cancelled, moved on reassignment, no-show, bundle cancelled, extended, or checked out)
     * (#228 point 6).
     *
     * @param actorId the ID of the user triggering the cancellation, or null for system events
     */
    @Transactional
    public void cancelPendingTransferIfExists(
            Long reservationId, LocalDateTime now, Long actorId, String auditReason, String notificationMessage) {
        reservationTransferRepository
                .findByReservationIdAndStatus(reservationId, TransferStatus.PENDING)
                .ifPresent(transfer -> {
                    transfer.cancel(now);
                    reservationTransferRepository.save(transfer);
                    auditService.record(
                            actorId, "TRANSFER_CANCELLED", "ReservationTransfer", transfer.getId(), auditReason);
                    ReservationServiceSupport.notifyUserAfterCommit(
                            notificationService,
                            transfer.getToUserId(),
                            "Reservation Transfer Cancelled",
                            notificationMessage);
                });
    }
}
