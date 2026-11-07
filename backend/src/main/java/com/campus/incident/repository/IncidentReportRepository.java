package com.campus.incident.repository;

import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {

    Page<IncidentReport> findByStatus(IncidentStatus status, Pageable pageable);

    // My incidents: only the ones this user reported.
    Page<IncidentReport> findByReportedById(Long reportedById, Pageable pageable);

    // Bug fixed (#159): "is any incident still active on this asset?" used to load whole rows (with their
    // TEXT columns) just to call isEmpty(). exists... asks the database for a yes/no only.
    boolean existsByAssetIdAndStatusIn(Long assetId, Collection<IncidentStatus> statuses);
}
