package com.example.webflux.service.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;

import com.example.webflux.dto.DocumentStatusResponse;
import com.example.webflux.etl.readers.MarkdownDocumentReader;
import com.example.webflux.etl.readers.PdfDocumentReader;
import com.example.webflux.etl.transformers.ContentFormatTransformer;
import com.example.webflux.etl.transformers.DocumentChunkTransformer;
import com.example.webflux.etl.writers.VectorStoreDocumentWriter;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.DocumentManagementService;
import com.example.webflux.util.DocumentHashUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentManagementServiceImpl extends EgovAbstractServiceImpl implements DocumentManagementService {

    @Value("${app.document.upload-dir:C:/workspace-test/upload/data}")
    private String uploadDir;

    private final MarkdownDocumentReader markdownReader;
    private final PdfDocumentReader pdfReader;
    private final ContentFormatTransformer contentFormatTransformer;
    private final DocumentChunkTransformer documentChunkTransformer;
    private final VectorStoreDocumentWriter vectorStoreWriter;
    private final PgVectorStore pgVectorStore;
    private final DocumentMetadataRepository metadataRepository;
    private final Executor documentProcessingExecutor;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger changedCount = new AtomicInteger(0);

    @Override
    public boolean isProcessing() {
        return isProcessing.get();
    }

    @Override
    public DocumentStatusResponse getStatusResponse() {
        return new DocumentStatusResponse(
                isProcessing.get(),
                processedCount.get(),
                totalCount.get(),
                changedCount.get());
    }

    @Override
    public CompletableFuture<Integer> loadDocumentsAsync() {
        if (isProcessing.get()) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }

        log.info("ETL 파이프라인으로 문서 처리 시작");
        isProcessing.set(true);
        processedCount.set(0);
        totalCount.set(0);
        changedCount.set(0);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1단계: 마크다운과 PDF 문서 읽기
                List<Document> markdownDocuments = markdownReader.get();
                List<Document> pdfDocuments = pdfReader.get();

                List<Document> allDocuments = new ArrayList<>();
                allDocuments.addAll(markdownDocuments);
                allDocuments.addAll(pdfDocuments);

                totalCount.set(allDocuments.size());
                log.info("총 {}개의 문서를 로드했습니다. (마크다운: {}개, PDF: {}개)",
                        allDocuments.size(), markdownDocuments.size(), pdfDocuments.size());

                // 2단계: 변경된 문서 필터링
                List<Document> changedDocuments = filterChangedDocuments(allDocuments);
                changedCount.set(changedDocuments.size());
                log.info("총 {}개의 문서 중 {}개의 변경된 문서를 처리합니다.",
                        allDocuments.size(), changedDocuments.size());

                if (changedDocuments.isEmpty()) {
                    log.info("변경된 문서가 없습니다. 인덱싱 작업을 건너뜁니다.");
                    return 0;
                }

                // 3단계: 문서 형식 정규화
                log.info("문서 형식 정규화 시작");
                List<Document> normalizedDocuments = contentFormatTransformer.apply(changedDocuments);
                log.info("문서 형식 정규화 완료: {}개 문서", normalizedDocuments.size());

                // 4단계: 문서 청크 분할
                log.info("문서 청크 분할 시작");
                List<Document> transformedDocuments = documentChunkTransformer.apply(normalizedDocuments);
                log.info("문서 청크 분할 완료: {}개 청크 생성", transformedDocuments.size());

                // 5단계: 벡터 저장소에 저장
                log.info("벡터 저장소 저장 시작");
                vectorStoreWriter.accept(transformedDocuments);
                log.info("벡터 저장소 저장 완료");

                // 6단계: 처리된 문서 해시 저장 (Document 개별)
                for (Document document : changedDocuments) {
                    saveDocumentHash(document);
                }

                processedCount.set(transformedDocuments.size());
                log.info("문서 처리 완료: 원본 {}개 → 청크 {}개",
                    changedDocuments.size(), transformedDocuments.size());

                return transformedDocuments.size();

            } catch (Exception e) {
                log.error("문서 처리 중 오류 발생", e);
                throw new RuntimeException("문서 처리 중 오류 발생", e);
            } finally {
                isProcessing.set(false);
            }
        }, documentProcessingExecutor);
    }

    @Override
    public String reindexDocuments() {
        log.info("문서 재인덱싱 요청 수신");
        CompletableFuture<Integer> future = this.loadDocumentsAsync();

        // 이미 인덱싱 중이면 0이 즉시 반환됨 → 예외로 알림
        if (future.isDone()) {
            try {
                if (future.get() == 0) {
                    throw new IllegalStateException("이미 문서 인덱싱이 진행 중입니다.");
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.error("상태 확인 중 오류", e);
                throw new RuntimeException("상태 확인 중 오류가 발생했습니다: " + e.getMessage(), e);
            }
        }

        future.thenAccept(count -> log.info("재인덱싱 완료: {}개 청크 처리됨", count))
              .exceptionally(throwable -> {
                  log.error("재인덱싱 중 오류 발생", throwable);
                  return null;
              });

        return "문서 재인덱싱이 처리되었습니다.";
    }

    @Override
    public Mono<Map<String, Object>> uploadMarkdownFiles(Flux<FilePart> files) {
        return files.collectList().flatMap(fileList -> {
            Map<String, Object> result = new HashMap<>();

            if (fileList.isEmpty()) {
                result.put("success", false);
                result.put("message", "업로드할 파일이 없습니다.");
                result.put("files", Collections.emptyList());
                return Mono.just(result);
            }

            if (fileList.size() > 5) {
                result.put("success", false);
                result.put("message", "최대 5개 파일만 업로드할 수 있습니다.");
                result.put("files", Collections.emptyList());
                return Mono.just(result);
            }

            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            return Flux.fromIterable(fileList)
                .flatMap(filePart -> {
                    String rawFilename = filePart.filename();
                    if (rawFilename == null || rawFilename.isBlank()) {
                        return Mono.error(new IllegalArgumentException("파일명이 없습니다."));
                    }
                    // 방어 1단계: 순수 파일명만 추출 (경로 탐색 문자 제거)
                    String filename = Paths.get(rawFilename).getFileName().toString();

                    if (!filename.endsWith(".md")) {
                        return Mono.error(new IllegalArgumentException("마크다운(.md) 파일만 업로드 가능합니다."));
                    }

                    File dest = new File(dir, filename);
                    try {
                        // 방어 2단계: 경로 탐색(Path Traversal) 방어
                        if (!dest.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                            return Mono.error(new IllegalArgumentException("허용되지 않는 파일 경로입니다: " + filename));
                        }
                    } catch (IOException e) {
                        return Mono.error(new IllegalArgumentException("파일 경로 검증 실패: " + e.getMessage()));
                    }
                    return filePart.transferTo(dest).thenReturn(filename);
                })
                .collectList()
                .map(uploadedFiles -> {
                    result.put("success", true);
                    result.put("uploaded", uploadedFiles.size());
                    result.put("files", uploadedFiles);
                    return result;
                })
                .onErrorResume(e -> {
                    result.put("success", false);
                    result.put("message", e.getMessage());
                    return Mono.just(result);
                });
        });
    }

    /**
     * 변경된 문서만 필터링한다.
     * (filename, chunkIndex) 복합 키로 페이지 단위 변경을 감지한다.
     * - PDF: (report.pdf, 0), (report.pdf, 1), ... (report.pdf, 159)
     * - 마크다운: (guide.md, 0)
     */
    private List<Document> filterChangedDocuments(List<Document> documents) {
        return documents.stream()
                .filter(this::isDocumentChanged)
                .toList();
    }

    /**
     * 문서가 변경되었는지 확인한다.
     * filename = source 메타데이터 (원본 파일명)
     * chunkIndex = page_number - 1 (PDF), 0 (마크다운)
     */
    private boolean isDocumentChanged(Document document) {
        String content = document.getText();

        if (content == null || content.trim().isEmpty()) {
            return false;
        }

        String filename = (String) document.getMetadata().get("source");
        int chunkIndex = getChunkIndex(document);

        String newHash = DocumentHashUtil.calculateHash(content);

        try {
            Optional<DocumentMetadata> metadataOpt = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

            if (metadataOpt.isPresent()) {
                String oldHash = metadataOpt.get().getContentHash();
                if (oldHash != null && oldHash.equals(newHash)) {
                    log.debug("문서 '{}' [{}] 변경 없음 (해시: {})", filename, chunkIndex, newHash);
                    return false;
                }
                log.info("문서 '{}' [{}] 해시 변경 감지 (기존: {} → 신규: {})", filename, chunkIndex, oldHash, newHash);
            } else {
                log.info("문서 '{}' [{}] 신규 문서 (저장된 해시 없음)", filename, chunkIndex);
            }
        } catch (Exception e) {
            log.error("문서 '{}' [{}] 해시 조회 중 오류 발생 — 변경으로 간주", filename, chunkIndex, e);
        }

        return true;
    }

    /**
     * 문서 처리 완료 후 해시값을 저장한다.
     * (filename, chunkIndex) 복합 키로 페이지마다 독립된 레코드에 저장한다.
     */
    private void saveDocumentHash(Document document) {
        String content = document.getText();

        if (content == null || content.trim().isEmpty()) {
            return;
        }

        String filename = (String) document.getMetadata().get("source");
        int chunkIndex = getChunkIndex(document);

        String newHash = DocumentHashUtil.calculateHash(content);

        try {
            Optional<DocumentMetadata> existing = metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

            DocumentMetadata metadata;
            if (existing.isPresent()) {
                metadata = existing.get();
                metadata.setContentHash(newHash);
                metadata.setIndexedAt(LocalDateTime.now());
            } else {
                metadata = new DocumentMetadata(null, filename, chunkIndex, newHash, LocalDateTime.now());
            }

            metadataRepository.save(metadata);
            log.debug("문서 '{}' [{}] 해시 저장 완료: {}", filename, chunkIndex, newHash);
        } catch (Exception e) {
            log.error("문서 '{}' [{}] 해시 저장 실패", filename, chunkIndex, e);
        }
    }

    /**
     * Document의 chunk index를 결정한다.
     * PDF: page_number 메타데이터(1-based) → 0-based로 변환
     * 마크다운: page_number 없음 → 0
     */
    private int getChunkIndex(Document document) {
        Object pageNumber = document.getMetadata().get("page_number");
        if (pageNumber instanceof Integer) {
            return (Integer) pageNumber - 1;
        }
        return 0;
    }

}
