package com.zzzzyj.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Milvus 删除补偿表
 * 当 ES 删除成功但 Milvus 删除失败时，写入此表，由定时任务异步重试
 */
@Data
@Entity
@Table(name = "milvus_pending_delete", indexes = {
        @Index(name = "idx_mpd_created_at", columnList = "created_at"),
        @Index(name = "idx_mpd_file_md5", columnList = "file_md5")
})
public class MilvusPendingDelete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", length = 64, nullable = false)
    private String fileMd5;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    public MilvusPendingDelete() {}

    public MilvusPendingDelete(String fileMd5) {
        this.fileMd5 = fileMd5;
    }
}
