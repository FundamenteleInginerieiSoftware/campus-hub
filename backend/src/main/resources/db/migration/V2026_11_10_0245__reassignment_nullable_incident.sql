-- Allow incident_id in reassignment_log to be NULL for non-incident administrative triggers
-- (such as chain-shifting conflict resolution and global optimal rebalancing).
ALTER TABLE reassignment_log ALTER COLUMN incident_id DROP NOT NULL;
