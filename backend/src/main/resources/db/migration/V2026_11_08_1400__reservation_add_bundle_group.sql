-- Migration to support simultaneous multi-asset reservations (bundle booking)
ALTER TABLE reservations ADD COLUMN bundle_id UUID;
CREATE INDEX idx_reservations_bundle_id ON reservations (bundle_id) WHERE bundle_id IS NOT NULL;
