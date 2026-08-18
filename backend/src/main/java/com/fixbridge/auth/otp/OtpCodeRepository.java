package com.fixbridge.auth.otp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /** The code a verify attempt is judged against: the most recent live one for this destination. */
    Optional<OtpCode> findFirstByDestinationAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String destination, OtpCode.Purpose purpose);

    /** How many codes were recently sent here — the send rate limit. */
    long countByDestinationAndPurposeAndCreatedAtAfter(
            String destination, OtpCode.Purpose purpose, Instant after);
}
