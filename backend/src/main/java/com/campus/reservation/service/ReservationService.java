package com.campus.reservation.service;

import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.repository.ReservationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing reservations and implementing the public {@link ReservationApi} facade.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService implements ReservationApi {

    private final ReservationRepository reservationRepository;

    @Override
    public List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from) {
        return List.of();
    }

    @Override
    public boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId) {
        return true;
    }

    @Override
    @Transactional
    public void moveToAsset(Long reservationId, Long newAssetId, String reason) {
        // Stub implementation; full business logic will be provided in feature branch
    }

    @Override
    @Transactional
    public void forceCancel(Long reservationId, String reason) {
        // Stub implementation; full business logic will be provided in feature branch
    }

    @Override
    public Optional<ReservationSummary> findCurrentCheckout(Long assetId) {
        return Optional.empty();
    }
}
