package com.campus.incident.web;

/** How urgently an admin should look at a unit. Units without failures are not listed at all. */
public enum ReliabilityLevel {
    /** At least 3 failures, or at least 2 and failing at least twice as often as its twins. */
    INSPECT,
    /** At least 1 failure, but not {@link #INSPECT}. */
    WATCH
}
