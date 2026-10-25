package com.campus.reservation.api;

/**
 * Thrown when an automated or manual reassignment cannot move a reservation to the chosen substitute
 * asset because it is no longer free.
 */
public class SubstituteNoLongerFreeException extends RuntimeException {

    public SubstituteNoLongerFreeException(String message) {
        super(message);
    }

    public SubstituteNoLongerFreeException(String message, Throwable cause) {
        super(message, cause);
    }
}
