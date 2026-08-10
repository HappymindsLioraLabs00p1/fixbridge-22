package com.fixbridge.repair;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One turn of a conversation. Images are stored as object keys, never as signed URLs — a stored
 *  URL would outlive its signature and break silently. */
@Entity
@Table(name = "conversation_messages")
@Getter @Setter @NoArgsConstructor
public class ConversationMessage {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private String role;

    @Column(columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "image_keys", columnDefinition = "text[]")
    private String[] imageKeys = new String[0];

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
