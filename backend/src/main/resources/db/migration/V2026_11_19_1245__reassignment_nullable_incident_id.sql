-- Allow administrative reassignments triggered by asset retirement (#245) without an incident report,
-- and record why each reassignment_log row exists (INCIDENT vs RETIRED).
ALTER TABLE reassignment_log
    ALTER COLUMN incident_id DROP NOT NULL,
    ADD COLUMN trigger VARCHAR(32) NOT NULL DEFAULT 'INCIDENT',
    ADD CONSTRAINT chk_reassignment_log_trigger_incident CHECK ((trigger = 'INCIDENT') = (incident_id IS NOT NULL));
