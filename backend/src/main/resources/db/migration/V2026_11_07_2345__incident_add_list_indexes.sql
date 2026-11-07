-- Indexes for the two incident lists (#159). Owned by Stefania (@m1runa-stefan1a).
-- Both lists sort by created_at DESC, id DESC (#108). PostgreSQL B-tree indexes can be read
-- backwards, so ascending columns serve the descending sort too.

-- GET /api/v1/incidents/me: WHERE reported_by_id = ? ORDER BY created_at DESC, id DESC
CREATE INDEX idx_incident_reports_reporter_created ON incident_reports (reported_by_id, created_at, id);

-- GET /api/v1/admin/incidents?status=OPEN: WHERE status = ? ORDER BY created_at DESC, id DESC.
-- It starts with status, so it also serves every "WHERE status = ?" query; the old one-column
-- index is no longer needed.
CREATE INDEX idx_incident_reports_status_created ON incident_reports (status, created_at, id);
DROP INDEX idx_incident_reports_status;

-- GET /api/v1/admin/incidents (no filter): ORDER BY created_at DESC, id DESC
CREATE INDEX idx_incident_reports_created ON incident_reports (created_at, id);
