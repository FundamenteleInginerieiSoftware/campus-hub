-- Composite index on (status, start_time) for scheduled no-show release sweep
-- (< cutoff) and preventive overdue cascade queries on CONFIRMED reservations.
CREATE INDEX idx_reservations_status_start ON reservations(status, start_time);

-- Index on reassignment_log(created_at) for admin audit log pagination.
-- PostgreSQL B-tree indexes are bidirectional, so an explicit DESC is unnecessary.
CREATE INDEX idx_reassignment_log_created_at ON reassignment_log(created_at);
