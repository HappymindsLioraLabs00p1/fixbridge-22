package com.fixbridge.job;

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

import java.time.Instant;
import java.util.UUID;

/** A photo/video attached to a job. Stored in a PRIVATE bucket; served only via short-lived signed URLs. */
@Entity
@Table(name = "job_media")
@Getter
@Setter
@NoArgsConstructor
public class JobMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "media_type", nullable = false)
    private String mediaType; // image | video

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public JobMedia(UUID jobId, String storageKey, String mediaType) {
        this.jobId = jobId;
        this.storageKey = storageKey;
        this.mediaType = mediaType;
    }
}
