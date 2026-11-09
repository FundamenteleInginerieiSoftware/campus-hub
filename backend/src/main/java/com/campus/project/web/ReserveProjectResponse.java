package com.campus.project.web;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReserveProjectResponse(UUID bundleId, LocalDateTime start, LocalDateTime end, List<ReservedItem> items) {}
