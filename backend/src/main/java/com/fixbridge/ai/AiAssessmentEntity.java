package com.fixbridge.ai;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.AssessmentStatus;
import com.fixbridge.common.enums.Complexity;
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
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Persisted structured AI assessment. The AI never sets price — only these technical signals. */
@Entity
@Table(name = "ai_assessments")
@Getter
@Setter
@NoArgsConstructor
public class AiAssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    private String category;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "ai_urgency")
    private AiUrgency urgency;

    private BigDecimal confidence;

    @Column(name = "recommended_trade")
    private String recommendedTrade;

    @Column(name = "professional_required")
    private Boolean professionalRequired;

    @Column(name = "safe_diy_allowed")
    private Boolean safeDiyAllowed;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "complexity")
    private Complexity complexity;

    @Column(name = "labor_hours_min")
    private BigDecimal laborHoursMin;

    @Column(name = "labor_hours_max")
    private BigDecimal laborHoursMax;

    /** Full validated structured output, stored as jsonb. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawJson;

    // --- Retry state (added for the separate AI service) -----------------------------------
    /** completed | pending | failed. Lets a job exist while its assessment is still being retried. */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "assessment_status", nullable = false)
    private AssessmentStatus status = AssessmentStatus.completed;

    @Column(nullable = false)
    private int attempts = 1;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
