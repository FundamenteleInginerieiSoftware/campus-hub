package com.campus.incident.repository;

import com.campus.incident.domain.IncidentReport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {

    List<IncidentReport> findByAssetId(Long assetId);

    List<IncidentReport> findByStatus(IncidentReport.IncidentStatus status);
}
