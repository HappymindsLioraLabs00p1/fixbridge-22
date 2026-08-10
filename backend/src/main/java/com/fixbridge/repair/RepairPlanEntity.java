package com.fixbridge.repair;

import com.fixbridge.common.enums.SafetyLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A structured repair plan as shown to the customer. */
@Entity
@Table(name = "repair_plans")
@Getter @Setter @NoArgsConstructor
public class RepairPlanEntity {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false, columnDefinition = "text")
    private String problem;

    private String category;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "safety_level", columnDefinition = "safety_level", nullable = false)
    private SafetyLevel safetyLevel;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "stop_conditions", columnDefinition = "text[]")
    private String[] stopConditions = new String[0];

    /** The full validated plan, so what the customer saw stays recoverable. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawJson;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
