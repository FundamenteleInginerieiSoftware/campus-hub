package com.campus.incident.repository;

import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {

    Page<IncidentReport> findByStatus(IncidentStatus status, Pageable pageable);

    // My incidents: only the ones this user reported.
    Page<IncidentReport> findByReportedById(Long reportedById, Pageable pageable);

    // Bug fixed (#159): "is any incident still active on this asset?" used to load whole rows (with their
    // TEXT columns) just to call isEmpty(). exists... asks the database for a yes/no only.
    boolean existsByAssetIdAndStatus(Long assetId, IncidentStatus status);

    boolean existsByAssetIdAndStatusAndSeverityIn(
            Long assetId, IncidentStatus status, Collection<IncidentSeverity> severities);

    // #229: how many reports of this student are still waiting for an admin.
    long countByReportedByIdAndStatus(Long reportedById, IncidentStatus status);

    /**
     * Is any incident still keeping this asset out of use? The same rule as {@link
     * IncidentReport#keepsAssetOutOfUse()}: IN_REPAIR, or OPEN with a HIGH/CRITICAL severity (#229).
     */
    default boolean existsIncidentKeepingAssetOutOfUse(Long assetId) {
        return existsByAssetIdAndStatus(assetId, IncidentStatus.IN_REPAIR)
                || existsByAssetIdAndStatusAndSeverityIn(
                        assetId, IncidentStatus.OPEN, List.of(IncidentSeverity.HIGH, IncidentSeverity.CRITICAL));
    }
}
