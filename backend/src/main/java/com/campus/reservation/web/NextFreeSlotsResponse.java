package com.campus.reservation.web;

import java.util.List;

public record NextFreeSlotsResponse(Long assetId, int durationMinutes, boolean bookable, List<FreeSlotOption> slots) {}
