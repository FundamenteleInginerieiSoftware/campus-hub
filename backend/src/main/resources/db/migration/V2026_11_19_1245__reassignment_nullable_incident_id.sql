-- Allow administrative reassignments triggered by asset retirement (#245) without an incident report.
ALTER TABLE reassignment_log
    ALTER COLUMN incident_id DROP NOT NULL;
