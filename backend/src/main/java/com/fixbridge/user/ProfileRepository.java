package com.fixbridge.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    Optional<Profile> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Phone sign-in lookup. Phones are stored normalised (E.164) by the OTP flow, but the column has
     * no unique constraint and older rows may hold anything — "first by creation" makes a duplicate
     * phone deterministic (the longest-standing account wins) instead of an exception.
     */
    Optional<Profile> findFirstByPhoneOrderByCreatedAtAsc(String phone);
}
