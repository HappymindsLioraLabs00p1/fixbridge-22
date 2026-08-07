package com.fixbridge.common.enums;

/** Matches PostgreSQL {@code document_status} — the review state of a contractor's paperwork. */
public enum DocumentStatus {
    pending, valid, expiring, expired, rejected
}
