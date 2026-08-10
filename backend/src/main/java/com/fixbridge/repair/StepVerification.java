package com.fixbridge.repair;

import com.fixbridge.common.enums.VerificationResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The outcome of checking a customer's progress photo. Kept as a record rather than a flag so an
 *  escalation can be audited later — "why did we tell them to stop?" must be answerable. */
@Entity
@Table(name = "repair_step_verifications")
@Getter @Setter @NoArgsConstructor
public class StepVerification {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "verification_result", nullable = false)
    private VerificationResult result;

    private BigDecimal confidence;

    @Column(columnDefinition = "text")
    private String reason;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "image_keys", columnDefinition = "text[]")
    private String[] imageKeys = new String[0];

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
