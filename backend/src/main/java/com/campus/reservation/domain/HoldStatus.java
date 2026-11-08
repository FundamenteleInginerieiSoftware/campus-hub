package com.campus.reservation.domain;

/**
 * Lifecycle states of a temporary reservation hold:
 * <ul>
 *   <li>{@code ACTIVE}: Slot is held for the user; expires in 1 minute if unconfirmed.</li>
 *   <li>{@code CONFIRMED}: User converted the hold into a real confirmed reservation.</li>
 *   <li>{@code RELEASED}: User cancelled/abandoned the hold early, freeing the slot immediately.</li>
 *   <li>{@code EXPIRED}: TTL elapsed without confirmation; slot is released for others.</li>
 * </ul>
 */
public enum HoldStatus {
    ACTIVE,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
