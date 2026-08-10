package com.fixbridge.repair;

import com.fixbridge.common.enums.StepState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One step of a repair plan, with its own state so progress survives the customer closing the app. */
@Entity
@Table(name = "repair_steps")
@Getter @Setter @NoArgsConstructor
public class RepairStep {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String instruction;

    @Column(columnDefinition = "text")
    private String why;

    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]")
    private String[] tools = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]")
    private String[] parts = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY) @Column(columnDefinition = "text[]")
    private String[] warnings = new String[0];

    @Column(name = "expected_result", columnDefinition = "text")
    private String expectedResult;

    @Column(name = "requires_image_verification", nullable = false)
    private boolean requiresImageVerification;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "step_state", nullable = false)
    private StepState state = StepState.pending;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
