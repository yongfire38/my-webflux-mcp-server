package com.example.webflux.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.document.Document;
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
import com.example.webflux.util.StaleVectorCleaner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentManagementServiceImpl extends EgovAbstractServiceImpl implements DocumentManagementService {

    private final MarkdownDocumentReader markdownReader;
    private final PdfDocumentReader pdfReader;
    private final ContentFormatTransformer contentFormatTransformer;
    private final DocumentChunkTransformer documentChunkTransformer;
    private final VectorStoreDocumentWriter vectorStoreWriter;
    private final DocumentMetadataRepository metadataRepository;
    private final StaleVectorCleaner staleVectorCleaner;
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
        log.info("ETL 파이프라인으로 문서 처리 시작");
        // compareAndSet으로 중복 실행을 원자적으로 방지
        if (!isProcessing.compareAndSet(false, true)) {
            log.warn("이미 문서 처리가 진행 중입니다.");
            return CompletableFuture.completedFuture(0);
        }
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

                // 이슈 #23: 디스크에서 삭제·이름 변경된 파일의 고아(orphan) 벡터·메타데이터 정리
                // 현재 디스크에 존재하는 파일명 집합과 DB에 기록된 파일명 집합의 차집합을 구해 삭제
                cleanupOrphanDocuments(allDocuments);

                // 2단계: 변경된 문서 필터링
                List<Document> changedDocuments = filterChangedDocuments(allDocuments);
                changedCount.set(changedDocuments.size());
                log.info("총 {}개의 문서 중 {}개의 변경된 문서를 처리합니다.",
                        allDocuments.size(), changedDocuments.size());

                if (changedDocuments.isEmpty()) {
                    log.info("변경된 문서가 없습니다. 인덱싱 작업을 건너뜁니다.");
                    return 0;
                }

                // 2-1단계: 변경된 문서의 기존 stale 벡터 선삭제
                // PDF는 페이지 단위(page_number), MD는 파일 전체(source)로 삭제 범위를 한정
                for (Document doc : changedDocuments) {
                    String src = (String) doc.getMetadata().get("source");
                    Object pageNum = doc.getMetadata().get("page_number");
                    if (pageNum instanceof Integer p) {
                        staleVectorCleaner.deleteBySourceAndPage(src, p);
                    } else {
                        staleVectorCleaner.deleteBySource(src);
                    }
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
        // isProcessing 선체크: 이미 진행 중이면 즉시 예외 (컨트롤러에서 409로 매핑됨)
        if (isProcessing.get()) {
            throw new IllegalStateException("이미 문서 인덱싱이 진행 중입니다.");
        }

        CompletableFuture<Integer> future = this.loadDocumentsAsync();
        future.thenAccept(count -> log.info("재인덱싱 완료: {}개 청크 처리됨", count))
              .exceptionally(throwable -> {
                  log.error("재인덱싱 중 오류 발생", throwable);
                  return null;
              });

        return "문서 재인덱싱이 처리되었습니다.";
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
                // REST 경로(서버 파일시스템 인덱싱)는 클라이언트 출처 및 요약 없음 — null
                metadata = new DocumentMetadata(null, filename, chunkIndex, newHash, LocalDateTime.now(), null, null);
            }

            metadataRepository.save(metadata);
            log.debug("문서 '{}' [{}] 해시 저장 완료: {}", filename, chunkIndex, newHash);
        } catch (Exception e) {
            log.error("문서 '{}' [{}] 해시 저장 실패", filename, chunkIndex, e);
        }
    }

    /**
     * 이슈 #23: 디스크에서 삭제·이름 변경된 파일의 고아 벡터·메타데이터를 일괄 정리합니다.
     *
     * 디스크에 없는데 DB에는 기록이 남아 있는 파일(orphan)을 찾아
     * document_metadata와 vector_store에서 해당 파일 레코드를 모두 삭제합니다.
     * REST 경로(reindex)에서만 호출되며, MCP 경로는 명시적 파일명 처리이므로 해당 없음.
     */
    private void cleanupOrphanDocuments(List<Document> diskDocuments) {
        try {
            // 현재 디스크에 존재하는 파일명 집합
            java.util.Set<String> diskFilenames = diskDocuments.stream()
                    .map(d -> (String) d.getMetadata().get("source"))
                    .filter(s -> s != null)
                    .collect(java.util.stream.Collectors.toSet());

            // DB에 기록된 파일명 집합
            List<String> dbFilenames = metadataRepository.findAllDistinctFilenames();

            // DB에는 있지만 디스크에는 없는 고아 파일명
            List<String> orphans = dbFilenames.stream()
                    .filter(f -> !diskFilenames.contains(f))
                    .toList();

            if (orphans.isEmpty()) {
                log.debug("[고아 정리] 고아 문서 없음");
                return;
            }

            log.info("[고아 정리] {}개 파일 고아 감지 — 벡터·메타데이터 삭제: {}", orphans.size(), orphans);
            for (String filename : orphans) {
                try {
                    staleVectorCleaner.deleteBySource(filename);
                    metadataRepository.deleteByFilename(filename);
                    log.info("[고아 정리] {} 삭제 완료", filename);
                } catch (Exception e) {
                    log.error("[고아 정리] {} 삭제 실패: {}", filename, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[고아 정리] 고아 정리 중 오류 발생 — 이 오류는 무시하고 인덱싱을 계속합니다: {}", e.getMessage());
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
