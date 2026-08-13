package com.fixbridge.contractor;

import com.fixbridge.common.enums.ContractorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contractors")
@Getter
@Setter
@NoArgsConstructor
public class Contractor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "contractor_status", nullable = false)
    private ContractorStatus status = ContractorStatus.draft;

    @Column(name = "min_trip_charge_cents")
    private Long minTripChargeCents = 0L;

    // The published rate card. A homeowner is shown one of these before dispatch and authorises
    // that exact amount, so each is the total for its circumstance rather than an increment —
    // quoting a base plus a surcharge invites a bill that doesn't match what was agreed.
    //
    // This is fee B in the pricing model: the contractor's own visit charge. It is distinct from
    // the FixBridge coordination fee, which may be waived during beta, and from the repair
    // estimate, which is agreed after diagnosis.
    @Column(name = "visit_fee_cents", nullable = false)
    private Long visitFeeCents = 0L;

    @Column(name = "emergency_fee_cents", nullable = false)
    private Long emergencyFeeCents = 0L;

    @Column(name = "after_hours_fee_cents", nullable = false)
    private Long afterHoursFeeCents = 0L;

    @Column(name = "weekend_fee_cents", nullable = false)
    private Long weekendFeeCents = 0L;

    /** Charged on a late cancellation or a wasted trip. */
    @Column(name = "cancellation_fee_cents", nullable = false)
    private Long cancellationFeeCents = 0L;

    /** The floor for labour on any job, before parts. */
    @Column(name = "minimum_labor_cents", nullable = false)
    private Long minimumLaborCents = 0L;

    @Column(name = "travel_radius_miles")
    private Integer travelRadiusMiles = 25;

    // Where the contractor is based. Nullable on purpose: someone who hasn't supplied a location is
    // still dispatchable, they just can't be ranked by distance.
    private java.math.BigDecimal latitude;
    private java.math.BigDecimal longitude;

    @Column(name = "service_city")
    private String serviceCity;

    @Column(name = "service_postal_code")
    private String servicePostalCode;

    // Stripe Connect
    @Column(name = "stripe_account_id", unique = true)
    private String stripeAccountId;

    @Column(name = "connect_onboarded", nullable = false)
    private boolean connectOnboarded = false;

    @Column(name = "payouts_enabled", nullable = false)
    private boolean payoutsEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** A contractor may receive jobs/payouts only when approved and Connect onboarding is complete. */
    public boolean isEligibleForWork() {
        return status == ContractorStatus.approved && connectOnboarded && payoutsEnabled;
    }
}
