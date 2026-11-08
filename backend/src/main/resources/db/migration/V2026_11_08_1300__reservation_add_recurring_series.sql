-- Migration to support recurring reservation series
ALTER TABLE reservations ADD COLUMN recurring_series_id UUID;
CREATE INDEX idx_reservations_recurring_series_id ON reservations (recurring_series_id) WHERE recurring_series_id IS NOT NULL;
