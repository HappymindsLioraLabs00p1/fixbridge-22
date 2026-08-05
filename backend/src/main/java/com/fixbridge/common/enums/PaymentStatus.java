package com.fixbridge.common.enums;

/** Matches PostgreSQL {@code payment_status}. */
public enum PaymentStatus {
    requires_payment, processing, succeeded, failed, refunded, disputed, canceled
}
