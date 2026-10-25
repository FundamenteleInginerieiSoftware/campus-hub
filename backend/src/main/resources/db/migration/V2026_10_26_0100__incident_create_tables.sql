DROP TABLE IF EXISTS reassignment_log CASCADE;
DROP TABLE IF EXISTS incident_reports CASCADE;

CREATE TABLE incident_reports (
                                  id BIGSERIAL PRIMARY KEY,
                                  asset_id BIGINT NOT NULL,
                                  reservation_id BIGINT,
                                  reported_by_id BIGINT NOT NULL,
                                  description TEXT NOT NULL,
                                  severity VARCHAR(32) NOT NULL,
                                  status VARCHAR(32) NOT NULL,
                                  photo_key VARCHAR(255),
                                  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                  resolved_at TIMESTAMP WITH TIME ZONE,
                                  resolved_by_id BIGINT,
                                  resolution_notes TEXT,
                                  version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_incident_reports_asset_id ON incident_reports(asset_id);
CREATE INDEX idx_incident_reports_status ON incident_reports(status);
