package com.campus.incident.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The equipment reliability report for the window {@code [from, to]}. Only units with at least one failure
 * are in {@code items}, so the list is short and not paged.
 */
public record ReliabilityReportResponse(
        int windowDays, LocalDateTime from, LocalDateTime to, List<AssetReliabilityItem> items) {}
