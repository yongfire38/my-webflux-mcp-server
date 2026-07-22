package com.example.webflux.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

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

    /** 업로드 출처 클라이언트 식별자 (MCP clientInfo.name). REST 경로로 인덱싱된 문서는 null. */
    private String sourceClient;

    /** 문서 전체 요약 (MCP Sampling으로 생성). 동일 파일의 모든 청크에 동일 값 저장. REST 경로는 null. */
    @Column(length = 2000)
    private String summary;
}
