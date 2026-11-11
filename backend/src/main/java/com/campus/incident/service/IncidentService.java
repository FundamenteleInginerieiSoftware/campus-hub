package com.campus.incident.service;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.BusinessRuleException;
import com.campus.common.error.InvalidParameterException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.common.error.NotFoundException;
import com.campus.common.paging.PageResponse;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.incident.domain.IncidentReport;
import com.campus.incident.domain.IncidentReport.IncidentStatus;
import com.campus.incident.repository.IncidentReportRepository;
import com.campus.incident.web.CreateIncidentRequest;
import com.campus.incident.web.IncidentResponse;
import com.campus.storage.api.StorageService;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class IncidentService {

    /**
     * #229: a student can have at most this many reports still OPEN (waiting for an admin). Without it, one
     * student could report every asset id in a loop. Admins have no limit.
     */
    static final int MAX_OPEN_REPORTS_PER_STUDENT = 3;

    /** Same limit as the other lists in the app (catalog, reservations, reassignment log). */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * Newest first. createdAt only has whole seconds, so two incidents reported in the same second
     * would come back in a random order; the id (always growing) breaks the tie.
     */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final IncidentReportRepository incidentReportRepository;
    private final CatalogApi catalogApi;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final StorageService storageService;
    private final Clock clock;

    public IncidentService(
            IncidentReportRepository incidentReportRepository,
            CatalogApi catalogApi,
            ApplicationEventPublisher eventPublisher,
            AuditService auditService,
            StorageService storageService,
            Clock clock) {
        this.incidentReportRepository = incidentReportRepository;
        this.catalogApi = catalogApi;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.storageService = storageService;
        this.clock = clock;
    }

    /**
     * Saves the report and, for HIGH or CRITICAL, takes the asset out of use, all in one transaction.
     *
     * <p>Bug fixed (#229): every report used to take the asset out of use, even LOW ("cosmetic, it still
     * works"), and moved or cancelled everyone's bookings on it, so any student could block any asset. Now
     * only HIGH and CRITICAL do that at once; LOW and MEDIUM are tickets that wait for an admin (startRepair).
     * A student can also have at most {@link #MAX_OPEN_REPORTS_PER_STUDENT} OPEN reports (409
     * TOO_MANY_OPEN_INCIDENTS).
     *
     * <p>Bug fixed (#164): the asset row is locked FIRST (SELECT ... FOR UPDATE), which also throws 404
     * ASSET_NOT_FOUND / 409 ASSET_RETIRED, so a report for a missing or retired asset saves nothing, and a
     * report always waits for a resolve or reject running on the same asset instead of racing with it.
     *
     * <p>Calin's reassignment engine reacts to the event after the commit, when the asset is already
     * UNDER_MAINTENANCE, so it can never be picked as its own substitute.
     */
    @Transactional
    public IncidentResponse reportIncident(CreateIncidentRequest request, Long reporterId, boolean reporterIsAdmin) {
        if (!reporterIsAdmin
                && incidentReportRepository.countByReportedByIdAndStatus(reporterId, IncidentStatus.OPEN)
                        >= MAX_OPEN_REPORTS_PER_STUDENT) {
            throw new BusinessRuleException(
                    "TOO_MANY_OPEN_INCIDENTS",
                    "You already have " + MAX_OPEN_REPORTS_PER_STUDENT
                            + " open reports; wait until an admin has looked at them");
        }

        boolean outOfUse = request.severity().takesAssetOutOfUseAtOnce();
        if (outOfUse) {
            catalogApi.markUnderMaintenance(request.assetId());
        } else {
            lockAndRefuseRetired(request.assetId());
        }

        IncidentReport report = new IncidentReport(
                request.assetId(), null, reporterId, request.description(), request.severity(), null, now());
        IncidentReport saved = incidentReportRepository.save(report);
        audit(reporterId, "INCIDENT_REPORTED", saved, saved.getSeverity().name());

        if (outOfUse) {
            eventPublisher.publishEvent(
                    new AssetDamagedEvent(saved.getId(), saved.getAssetId(), reporterId, clock.instant()));
        }
        return toResponse(saved);
    }

    /**
     * OPEN to IN_REPAIR. Starting twice returns the incident unchanged. From RESOLVED or REJECTED: 409
     * INVALID_INCIDENT_STATUS_TRANSITION.
     *
     * <p>#229: for a LOW or MEDIUM report this is where the admin confirms the damage, so an AVAILABLE asset
     * goes UNDER_MAINTENANCE now and the reassignment engine moves its bookings (same event as a HIGH
     * report). An asset already under maintenance (a HIGH report) or retired is left as it is.
     */
    @Transactional
    public IncidentResponse startRepair(Long incidentId, Long adminId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.IN_REPAIR) {
            return toResponse(report);
        }

        boolean takesAssetOutNow = !report.keepsAssetOutOfUse();
        report.startRepair();
        boolean assetWasAvailable = false;
        if (takesAssetOutNow) {
            catalogApi.lockForBooking(report.getAssetId());
            assetWasAvailable = catalogApi.getAsset(report.getAssetId()).status() == AssetStatus.AVAILABLE;
            if (assetWasAvailable) {
                catalogApi.markUnderMaintenance(report.getAssetId());
            }
        }

        IncidentReport updated = incidentReportRepository.save(report);
        audit(adminId, "INCIDENT_REPAIR_STARTED", updated, null);
        if (assetWasAvailable) {
            eventPublisher.publishEvent(new AssetDamagedEvent(
                    updated.getId(), updated.getAssetId(), updated.getReportedById(), clock.instant()));
        }
        return toResponse(updated);
    }

    /**
     * Resolves an OPEN or IN_REPAIR incident. Resolving twice returns the incident unchanged. If this incident
     * kept the asset out of use, the asset becomes AVAILABLE again once no other incident keeps it out of use.
     * A LOW or MEDIUM report that was never started did not touch the asset, so closing it does not either.
     */
    @Transactional
    public IncidentResponse resolveIncident(Long incidentId, String resolutionNotes, Long adminId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.RESOLVED) {
            return toResponse(report);
        }

        boolean keptAssetOutOfUse = report.keepsAssetOutOfUse();
        report.resolve(adminId, resolutionNotes, now());
        IncidentReport updated = incidentReportRepository.save(report);
        if (keptAssetOutOfUse) {
            releaseAssetIfNoOtherIncident(updated.getAssetId());
        }
        audit(adminId, "INCIDENT_RESOLVED", updated, resolutionNotes);

        return toResponse(updated);
    }

    /**
     * Rejects an OPEN or IN_REPAIR incident (the report was wrong). Rejecting twice returns it unchanged.
     * Same asset rule as resolve. Bookings the engine already moved stay where they are.
     */
    @Transactional
    public IncidentResponse rejectIncident(Long incidentId, String resolutionNotes, Long adminId) {
        IncidentReport report = findOrThrow(incidentId);

        if (report.getStatus() == IncidentStatus.REJECTED) {
            return toResponse(report);
        }

        boolean keptAssetOutOfUse = report.keepsAssetOutOfUse();
        report.reject(adminId, resolutionNotes, now());
        IncidentReport updated = incidentReportRepository.save(report);
        if (keptAssetOutOfUse) {
            releaseAssetIfNoOtherIncident(updated.getAssetId());
        }
        audit(adminId, "INCIDENT_REJECTED", updated, resolutionNotes);

        return toResponse(updated);
    }

    /** Admin queue, newest first, optionally only one status. */
    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getAllIncidents(IncidentStatus status, int page, int size) {
        Pageable pageable = pageRequest(page, size);
        Page<IncidentReport> result = (status != null)
                ? incidentReportRepository.findByStatus(status, pageable)
                : incidentReportRepository.findAll(pageable);

        return PageResponse.from(result, this::toResponse);
    }

    /** The incidents this user reported (My incidents page), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> getMyIncidents(Long reporterId, int page, int size) {
        Page<IncidentReport> result = incidentReportRepository.findByReportedById(reporterId, pageRequest(page, size));
        return PageResponse.from(result, this::toResponse);
    }

    /**
     * One incident, for its reporter or an admin. Anyone else gets the same 404 as for a missing id, so
     * nobody can find out which incident ids exist.
     */
    @Transactional(readOnly = true)
    public IncidentResponse getIncident(Long incidentId, Long requesterId, boolean requesterIsAdmin) {
        return toResponse(findVisibleOrThrow(incidentId, requesterId, requesterIsAdmin));
    }

    /**
     * Adds or replaces the photo of an OPEN or IN_REPAIR incident, for its reporter or an admin (anyone else:
     * 404, like a missing id). StorageService checks the file: 400 INVALID_PHOTO_TYPE (not JPEG/PNG/WebP,
     * empty, or the first bytes are not really that image type), 413 PHOTO_TOO_LARGE (over 5 MB).
     *
     * <p>The client's file name is ignored on purpose: the type comes from the Content-Type and the
     * file's first bytes ("magic bytes"), never from the name, so a renamed .html can't pass as a .png.
     */
    @Transactional
    public IncidentResponse attachPhoto(
            Long incidentId,
            InputStream content,
            long size,
            String contentType,
            Long requesterId,
            boolean requesterIsAdmin) {
        IncidentReport report = findVisibleOrThrow(incidentId, requesterId, requesterIsAdmin);
        report.checkPhotoCanChange();

        String oldKey = report.getPhotoKey();
        String newKey = storageService.store(content, size, contentType, null);
        report.attachPhoto(newKey);
        IncidentReport saved = incidentReportRepository.save(report);

        cleanUpFilesAfterTransaction(oldKey, newKey);
        audit(requesterId, oldKey == null ? "INCIDENT_PHOTO_ADDED" : "INCIDENT_PHOTO_REPLACED", saved, null);
        return toResponse(saved);
    }

    /** The photo of one incident, for its reporter or an admin. 404 PHOTO_NOT_FOUND when it has none. */
    @Transactional(readOnly = true)
    public IncidentPhoto loadPhoto(Long incidentId, Long requesterId, boolean requesterIsAdmin) {
        IncidentReport report = findVisibleOrThrow(incidentId, requesterId, requesterIsAdmin);
        String key = report.getPhotoKey();
        if (key == null) {
            throw new NotFoundException("PHOTO_NOT_FOUND", "Photo was not found");
        }
        return new IncidentPhoto(storageService.load(key), StorageService.contentTypeForKey(key));
    }

    /**
     * Bug fixed (#146, #164): page and size come straight from the URL. A huge page made the offset
     * (page * size) bigger than an int, which Hibernate cannot use, so the client got a 500. Now a bad
     * value is a 400 INVALID_PARAMETER, like in the catalog and reservation lists.
     */
    static Pageable pageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidParameterException("Page index must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidParameterException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidParameterException("Page offset exceeds maximum supported value");
        }
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    private IncidentReport findOrThrow(Long incidentId) {
        return incidentReportRepository.findById(incidentId).orElseThrow(() -> notFound(incidentId));
    }

    // The reporter or an admin; anyone else gets the same 404 as for a missing id.
    private IncidentReport findVisibleOrThrow(Long incidentId, Long requesterId, boolean requesterIsAdmin) {
        IncidentReport report = findOrThrow(incidentId);
        if (!requesterIsAdmin && !report.getReportedById().equals(requesterId)) {
            throw notFound(incidentId);
        }
        return report;
    }

    /**
     * Bug fixed (#171): files are not part of the database transaction. Before, a rollback after
     * StorageService.store left a file that no row pointed to. Now the cleanup runs once the transaction
     * is over: after a commit the replaced photo is deleted; after a rollback the new file is deleted.
     * Without a transaction (plain unit tests) the old photo is deleted straight away.
     */
    private void cleanUpFilesAfterTransaction(String oldKey, String newKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(oldKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteQuietly(status == STATUS_COMMITTED ? oldKey : newKey);
            }
        });
    }

    // A file that could not be deleted only wastes space; it must never fail the request.
    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storageService.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Could not delete incident photo {}: {}", key, ex.getMessage());
        }
    }

    private static NotFoundException notFound(Long incidentId) {
        return new NotFoundException("INCIDENT_NOT_FOUND", "Incident report not found with id: " + incidentId);
    }

    /**
     * After resolve or reject: the asset is usable again only if no other incident still keeps it out of use.
     *
     * <p>Bug fixed (#108 review, race): the asset row is locked BEFORE asking "is any incident still
     * active?". reportIncident takes the same lock first, so a report and a resolve on the same asset run
     * one after the other. Before, both could check at the same moment and leave an asset AVAILABLE with an
     * OPEN incident, or UNDER_MAINTENANCE with none.
     *
     * <p>Bug fixed (#146): an asset retired while its incident was open stays RETIRED. markAvailable throws
     * 409 ASSET_RETIRED for it, which rolled back the resolve, so the incident could never be closed. Only an
     * asset that is still UNDER_MAINTENANCE goes back to AVAILABLE.
     */
    private void releaseAssetIfNoOtherIncident(Long assetId) {
        catalogApi.lockForBooking(assetId);

        if (incidentReportRepository.existsIncidentKeepingAssetOutOfUse(assetId)) {
            return;
        }
        if (catalogApi.getAsset(assetId).status() == AssetStatus.UNDER_MAINTENANCE) {
            catalogApi.markAvailable(assetId);
        }
    }

    // LOW/MEDIUM report: same lock and the same 404/409 as markUnderMaintenance, but the asset stays in use.
    private void lockAndRefuseRetired(Long assetId) {
        catalogApi.lockForBooking(assetId);
        if (catalogApi.getAsset(assetId).status() == AssetStatus.RETIRED) {
            throw new InvalidStateTransitionException("ASSET_RETIRED", "Cannot report an incident on a retired asset");
        }
    }

    /**
     * Bug fixed (#159): report, start-repair, resolve and reject were not in Liviu's audit log, so the
     * admin audit page could not show who closed an incident. Same transaction as the change (REQUIRED):
     * if the change rolls back, so does its audit entry. A repeated action that changes nothing
     * (resolve twice) returns before this and writes nothing.
     */
    private void audit(Long actorId, String action, IncidentReport report, String note) {
        String details = "Asset " + report.getAssetId() + (note == null || note.isBlank() ? "" : ": " + note);
        auditService.record(actorId, action, "INCIDENT", report.getId(), details);
    }

    // Whole seconds, like the contract format (2026-11-03T14:00:00), so POST and GET return the same value.
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private IncidentResponse toResponse(IncidentReport report) {
        return new IncidentResponse(
                report.getId(),
                report.getAssetId(),
                report.getReservationId(),
                report.getReportedById(),
                report.getDescription(),
                report.getSeverity(),
                report.getStatus(),
                report.getPhotoKey(),
                report.getCreatedAt(),
                report.getResolvedAt(),
                report.getResolvedById(),
                report.getResolutionNotes());
    }
}
