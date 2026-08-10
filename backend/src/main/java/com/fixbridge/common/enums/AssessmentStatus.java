package com.fixbridge.common.enums;

/** Matches PostgreSQL {@code assessment_status}. Lets a job exist while its assessment is retried. */
public enum AssessmentStatus {
    completed, pending, failed
}
