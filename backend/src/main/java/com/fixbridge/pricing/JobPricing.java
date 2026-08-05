package com.fixbridge.pricing;

import com.fixbridge.common.enums.AiUrgency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-job pricing ledger (subset of Appendix A). Retail low/high are null when pricing is withheld
 * pending an on-site assessment. Contractor-net columns are never exposed to the customer.
 */
@Entity
@Table(name = "job_pricing")
@Getter
@Setter
@NoArgsConstructor
public class JobPricing {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "ai_category")
    private String aiCategory;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "ai_urgency", columnDefinition = "ai_urgency")
    private AiUrgency aiUrgency;

    @Column(name = "ai_confidence")
    private BigDecimal aiConfidence;

    @Column(name = "est_contractor_net_low")
    private Long estContractorNetLow;

    @Column(name = "est_contractor_net_high")
    private Long estContractorNetHigh;

    @Column(name = "contractor_final_net_cents")
    private Long contractorFinalNetCents;

    @Column(name = "customer_retail_low")
    private Long customerRetailLow;

    @Column(name = "customer_retail_high")
    private Long customerRetailHigh;

    @Column(name = "customer_final_retail_cents")
    private Long customerFinalRetailCents;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public boolean isPriceAvailable() {
        return customerRetailLow != null && customerRetailHigh != null;
    }
}
