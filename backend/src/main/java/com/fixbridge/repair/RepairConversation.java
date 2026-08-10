package com.fixbridge.repair;

import com.fixbridge.common.enums.ConversationStatus;
import com.fixbridge.common.enums.SafetyLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A guided-repair conversation. Java owns this state; the Python service stays stateless. */
@Entity
@Table(name = "repair_conversations")
@Getter @Setter @NoArgsConstructor
public class RepairConversation {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** Set only if this conversation escalates into a dispatched job. */
    @Column(name = "job_id")
    private UUID jobId;

    private String category;

    @Column(columnDefinition = "text")
    private String problem;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "conversation_status", nullable = false)
    private ConversationStatus status = ConversationStatus.NEED_MORE_INFORMATION;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "safety_level", columnDefinition = "safety_level")
    private SafetyLevel safetyLevel;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp @Column(name = "updated_at")
    private Instant updatedAt;
}
