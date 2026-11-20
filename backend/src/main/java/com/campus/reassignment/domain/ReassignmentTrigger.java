package com.campus.reassignment.domain;

/**
 * Why a {@link ReassignmentLog} row was created: either a HIGH/CRITICAL incident report
 * ({@link #INCIDENT}) or an administrative asset retirement ({@link #RETIRED}, #245).
 */
public enum ReassignmentTrigger {
    INCIDENT,
    RETIRED
}
