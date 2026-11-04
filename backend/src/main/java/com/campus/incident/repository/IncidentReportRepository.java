package com.campus.incident.repository;

import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {

    Page<IncidentReport> findByStatus(IncidentStatus status, Pageable pageable);

    // My incidents: only the ones this user reported.
    Page<IncidentReport> findByReportedById(Long reportedById, Pageable pageable);

    List<IncidentReport> findByAssetIdAndStatusIn(Long assetId, List<IncidentStatus> statuses);
}
