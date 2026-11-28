-- Partial index supporting ReservationRepository.findCheckedOutOverlapping (#289, #362 §5).
CREATE INDEX idx_reservations_checked_out_at
    ON reservations (checked_out_at)
    WHERE checked_out_at IS NOT NULL;
