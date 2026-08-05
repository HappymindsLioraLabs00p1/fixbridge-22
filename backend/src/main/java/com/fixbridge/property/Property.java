package com.fixbridge.property;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "properties")
@Getter
@Setter
@NoArgsConstructor
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    private String label;

    @Column(nullable = false)
    private String line1;

    private String line2;
    private String city;
    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(nullable = false, length = 2)
    private String country = "US";

    private BigDecimal latitude;
    private BigDecimal longitude;

    @Column(name = "place_id")
    private String placeId;

    @Column(name = "property_type")
    private String propertyType;

    @Column(name = "access_notes")
    private String accessNotes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
