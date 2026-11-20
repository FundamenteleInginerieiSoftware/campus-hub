package com.campus.incident.service;

import java.time.LocalDateTime;

/**
 * One incident as the reliability calculator needs it, without the JPA entity.
 *
 * @param rejected the admin rejected the report, so the unit did not really fail
 * @param severe severity {@code HIGH} or {@code CRITICAL}
 * @param resolvedAt set only for a {@code RESOLVED} incident, otherwise {@code null}
 */
public record IncidentInput(
        Long assetId, boolean rejected, boolean severe, LocalDateTime createdAt, LocalDateTime resolvedAt) {}
