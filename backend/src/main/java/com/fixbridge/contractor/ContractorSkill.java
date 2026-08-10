package com.fixbridge.contractor;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A trade a contractor works. Separate rows rather than a list column, so "who does electrical
 *  work" is an indexed query rather than a string search. */
@Entity
@Table(name = "contractor_skills")
@Getter @Setter @NoArgsConstructor
public class ContractorSkill {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "contractor_id", nullable = false)
    private UUID contractorId;

    @Column(nullable = false)
    private String trade;

    /** Years in this trade specifically, not overall. */
    private Integer years;

    @Column(name = "is_primary", nullable = false)
    private boolean primarySkill;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
