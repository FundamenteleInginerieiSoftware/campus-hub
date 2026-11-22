package com.campus.reservation.service;

/**
 * Internal domain event published when an active reservation releases an equipment slot
 * (cancellation or no-show), triggering FIFO waitlist promotion after commit (#59).
 */
record SlotFreedEvent(Long assetId) {}
