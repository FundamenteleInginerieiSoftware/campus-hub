-- Rename reassignment_log.trigger to triggered_by so SQL tools that treat TRIGGER as a
-- reserved keyword do not need to quote the column (#311 follow-up).
ALTER TABLE reassignment_log
    DROP CONSTRAINT chk_reassignment_log_trigger_incident;

ALTER TABLE reassignment_log
    RENAME COLUMN trigger TO triggered_by;

ALTER TABLE reassignment_log
    ADD CONSTRAINT chk_reassignment_log_trigger_incident
        CHECK ((triggered_by = 'INCIDENT') = (incident_id IS NOT NULL));
