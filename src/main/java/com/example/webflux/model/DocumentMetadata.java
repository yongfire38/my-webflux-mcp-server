package com.example.webflux.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_metadata", uniqueConstraints = @UniqueConstraint(columnNames = {"filename", "chunkIndex"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false)
    private String contentHash;

    @Column(nullable = false)
    private LocalDateTime indexedAt;

    /** 업로드 소유자 식별자 (MCP clientInfo.name 또는 JWT userId). REST 경로로 인덱싱된 문서는 null. */
    @Column(name = "owner_user_id")
    private String ownerUserId;
}
