-- Enforce at most one PENDING transfer per reservation to prevent double-click race conditions (#228 point 3)
CREATE UNIQUE INDEX uq_reservation_transfers_pending ON reservation_transfers(reservation_id) WHERE status = 'PENDING';

-- Missing foreign keys to users(id) for from_user_id and to_user_id (#228 point 6)
ALTER TABLE reservation_transfers ADD CONSTRAINT fk_reservation_transfers_from_user FOREIGN KEY (from_user_id) REFERENCES users(id);
ALTER TABLE reservation_transfers ADD CONSTRAINT fk_reservation_transfers_to_user FOREIGN KEY (to_user_id) REFERENCES users(id);

