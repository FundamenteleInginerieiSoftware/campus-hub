package com.campus.reservation.service;

/**
 * Internal domain event published when an equipment slot is freed (reservation cancellation,
 * bundle or disabled-user cancellation, desk return including overdue items, auto-no-show, or hold
 * release/expiry), triggering FIFO waitlist promotion after commit (#59).
 */
record SlotFreedEvent(Long assetId) {}
