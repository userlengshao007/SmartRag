package com.zzzzyj.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_memories", indexes = {
        @Index(name = "idx_chat_memory_user_scope", columnList = "user_id,scope"),
        @Index(name = "idx_chat_memory_conversation", columnList = "conversation_id"),
        @Index(name = "idx_chat_memory_updated_at", columnList = "updated_at")
})
public class ChatMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemoryScope scope;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MemoryType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum MemoryScope {
        USER,
        CONVERSATION
    }

    public enum MemoryType {
        FACT,
        PREFERENCE,
        SUMMARY,
        TOOL_RESULT
    }
}
