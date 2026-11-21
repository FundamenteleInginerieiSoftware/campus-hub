package com.campus.incident.repository;

import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentSeverity;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {

    Page<IncidentReport> findByStatus(IncidentStatus status, Pageable pageable);

    // My incidents: only the ones this user reported.
    Page<IncidentReport> findByReportedById(Long reportedById, Pageable pageable);

    // Bug fixed (#159): "is any incident still active on this asset?" used to load whole rows (with their
    // TEXT columns) just to call isEmpty(). exists... asks the database for a yes/no only.
    boolean existsByAssetIdAndStatus(Long assetId, IncidentStatus status);

    boolean existsByAssetIdAndStatusAndSeverityIn(
            Long assetId, IncidentStatus status, Collection<IncidentSeverity> severities);

    // #291: every incident created in the reliability window (at or after `since`), whatever its status.
    List<IncidentReport> findByCreatedAtGreaterThanEqual(LocalDateTime since);

    // #229: how many reports of this student are still waiting for an admin.
    long countByReportedByIdAndStatus(Long reportedById, IncidentStatus status);

    // #229 review: transaction-scoped PostgreSQL advisory lock per student, the same trick as Calin's
    // ReservationRepository.lockUserQuota (#173). Two parallel reports on different assets lock different
    // asset rows, so without this they both count 2 OPEN reports and both pass the limit.
    // Key 1 is Calin's reservation quota, key 2 is ours (incident quota), so the two never block each other.
    // count(*) around it only because pg_advisory_xact_lock returns void, which Spring Data cannot map.
    @Query(
            value =
                    "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(2, CAST(:userId AS integer))) AS incident_quota_lock",
            nativeQuery = true)
    long lockReporterQuota(@Param("userId") Long userId);

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
