package com.example.webflux.service.impl;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springaicommunity.mcp.context.McpAsyncRequestContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import com.example.webflux.etl.transformers.ContentFormatTransformer;
import com.example.webflux.etl.transformers.DocumentChunkTransformer;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.util.DocumentHashUtil;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 클라이언트 로컬 문서를 수신하여 서버 벡터 DB에 임베딩하는 MCP 도구 서비스
 *
 * MCP 기능 활용:
 *   - Progress: 파이프라인 각 단계(읽기 → 요약 → 청킹 → 임베딩)를 클라이언트에 알림
 *   - Sampling: 클라이언트의 Ollama LLM에게 문서 요약 생성 위임
 *
 * 클라이언트 문서 경로: C:/workspace-test/upload/client_data
 * 지원 형식: PDF (.pdf), 마크다운 (.md)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentClientUploadServiceImpl extends EgovAbstractServiceImpl {

    private final ContentFormatTransformer contentFormatTransformer;
    private final DocumentChunkTransformer documentChunkTransformer;
    private final PgVectorStore pgVectorStore;
    private final DocumentMetadataRepository metadataRepository;

    private static final int PROGRESS_TOTAL_STEPS = 4;

    // 파이프라인 내 rawDocuments + chunks + summary를 함께 전달하기 위한 내부 레코드
    private record UploadContext(List<Document> rawDocuments, List<Document> chunks, String summary) {}

    /**
     * 클라이언트 로컬 문서를 base64로 수신하여 서버 벡터 DB에 임베딩합니다.
     *
     * 처리 흐름:
     *   1. base64 디코딩 → 텍스트 추출 (PDF/MD)   [Progress 1/4]
     *   2. Sampling → 클라이언트 Ollama로 문서 요약 요청   [Progress 2/4]
     *   3. 콘텐츠 정규화 + 청킹   [Progress 3/4]
     *   4. 벡터 저장소 임베딩 + 메타데이터 저장   [Progress 4/4]
     *
     * @param ctx          MCP 컨텍스트 (Progress/Sampling 전송용)
     * @param jobId        작업 추적용 고유 ID (클라이언트가 SSE 구독에 사용)
     * @param filename     파일명 (예: document.pdf, guide.md)
     * @param base64Content 파일 내용을 Base64 인코딩한 문자열
     * @param mimeType     MIME 타입: application/pdf 또는 text/markdown
     */
    @McpTool(
        name = "uploadAndIndexDocument",
        description = "클라이언트 로컬 문서(PDF/MD)를 base64로 수신하여 서버 RAG 벡터 DB에 임베딩합니다. "
                + "임베딩 진행률은 Progress로 전송되며, 문서 요약은 Sampling으로 클라이언트 Ollama에 위임합니다. "
                + "jobId는 클라이언트 SSE 진행률 스트림 구독에 사용됩니다."
    )
    public Mono<String> uploadAndIndexDocument(
            McpAsyncRequestContext ctx,
            @McpToolParam(description = "작업 추적용 고유 ID (UUID 권장)") String jobId,
            @McpToolParam(description = "파일명 (예: document.pdf, guide.md)") String filename,
            @McpToolParam(description = "파일 내용을 Base64 인코딩한 문자열") String base64Content,
            @McpToolParam(description = "MIME 타입: application/pdf 또는 text/markdown") String mimeType
    ) {
        log.info("[업로드] 요청 수신 — jobId: {}, filename: {}, mimeType: {}", jobId, filename, mimeType);

        return Mono.fromCallable(() -> {
            // 1단계: base64 디코딩 + 텍스트 추출
            byte[] fileBytes = decodeBase64(base64Content, jobId, filename);
            List<Document> rawDocuments = extractDocuments(fileBytes, filename, mimeType);
            log.info("[업로드][{}] 텍스트 추출 완료 — {}페이지", jobId, rawDocuments.size());

            return rawDocuments;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(rawDocuments ->
            // 1단계 완료 Progress 전송
            ctx.progress(p -> p.progress(1).total(PROGRESS_TOTAL_STEPS)
                    .message("텍스트 추출 완료 (" + rawDocuments.size() + "페이지)"))
               .thenReturn(rawDocuments)
        )
        .flatMap(rawDocuments ->
            // 2단계: Sampling — 클라이언트 Ollama에 문서 요약 요청
            requestSummaryViaSampling(ctx, rawDocuments, filename)
                .flatMap(summary -> {
                    log.info("[업로드][{}] Ollama 요약 완료 — 길이: {}자", jobId, summary.length());
                    return ctx.progress(p -> p.progress(2).total(PROGRESS_TOTAL_STEPS)
                            .message("요약 완료"))
                              .thenReturn(Map.entry(rawDocuments, summary));
                })
        )
        .flatMap(entry -> Mono.fromCallable(() -> {
            List<Document> rawDocuments = entry.getKey();
            String summary = entry.getValue();

            // 3단계: 정규화 + 청킹 (rawDocuments는 4단계 메타데이터 저장에도 필요)
            List<Document> normalized = contentFormatTransformer.apply(rawDocuments);
            List<Document> chunks = documentChunkTransformer.apply(normalized);
            log.info("[업로드][{}] 청킹 완료 — {}개 청크", jobId, chunks.size());

            return new UploadContext(rawDocuments, chunks, summary);
        }).subscribeOn(Schedulers.boundedElastic()))
        .flatMap(ctx3 ->
            ctx.progress(p -> p.progress(3).total(PROGRESS_TOTAL_STEPS)
                    .message("청킹 완료 (" + ctx3.chunks().size() + "개 청크)"))
               .thenReturn(ctx3)
        )
        .flatMap(ctx3 -> Mono.fromCallable(() -> {
            // 4단계: 벡터 저장소 임베딩 + 메타데이터 저장
            pgVectorStore.add(ctx3.chunks());
            // rawDocuments 기준으로 메타데이터 저장 (page_number 활용, loadDocumentsAsync와 동일 방식)
            saveMetadata(filename, ctx3.rawDocuments(), ctx3.summary());
            log.info("[업로드][{}] 임베딩 완료 — {}개 청크 저장", jobId, ctx3.chunks().size());

            return ctx3.chunks().size();
        }).subscribeOn(Schedulers.boundedElastic()))
        .flatMap(chunkCount ->
            ctx.progress(p -> p.progress(4).total(PROGRESS_TOTAL_STEPS)
                    .message("임베딩 완료 (" + chunkCount + "개 청크 저장)"))
               .thenReturn(String.format("[%s] %s 임베딩 완료 — %d개 청크가 RAG 지식 베이스에 추가되었습니다.",
                       jobId, filename, chunkCount))
        )
        .doOnError(e -> log.error("[업로드][{}] 처리 중 오류 발생 — {}", jobId, e.getMessage(), e))
        .onErrorReturn(String.format("[%s] %s 처리 중 오류가 발생했습니다.", jobId, filename));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private byte[] decodeBase64(String base64Content, String jobId, String filename) {
        try {
            return Base64.getDecoder().decode(base64Content.trim());
        } catch (IllegalArgumentException e) {
            log.error("[업로드][{}] Base64 디코딩 실패 — {}", jobId, filename);
            throw new IllegalArgumentException("Base64 디코딩 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파일 바이트 배열에서 Spring AI Document 목록을 추출합니다.
     * PDF: PagePdfDocumentReader (ByteArrayResource 사용)
     * MD:  UTF-8 문자열 → 단일 Document
     */
    private List<Document> extractDocuments(byte[] fileBytes, String filename, String mimeType) {
        boolean isPdf = "application/pdf".equalsIgnoreCase(mimeType)
                || filename.toLowerCase().endsWith(".pdf");

        if (isPdf) {
            return extractPdf(fileBytes, filename);
        } else {
            return extractMarkdown(fileBytes, filename);
        }
    }

    private List<Document> extractPdf(byte[] pdfBytes, String filename) {
        try {
            ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    resource,
                    PdfDocumentReaderConfig.builder()
                            .withPageTopMargin(0)
                            .withPagesPerDocument(1)
                            .build()
            );

            List<Document> pages = reader.read();
            List<Document> result = new ArrayList<>();

            for (int i = 0; i < pages.size(); i++) {
                Document page = pages.get(i);
                String content = page.getText();
                if (content == null || content.isBlank()) continue;

                Map<String, Object> meta = new HashMap<>(page.getMetadata());
                meta.put("source", filename);
                meta.put("file_name", filename);
                meta.put("type", "pdf");
                meta.put("page_number", i + 1);
                meta.put("upload_source", "client");
                result.add(new Document(content, meta));
            }

            log.info("[PDF 추출] {} — {}페이지", filename, result.size());
            return result;

        } catch (Exception e) {
            log.error("[PDF 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("PDF 추출 실패: " + e.getMessage(), e);
        }
    }

    private List<Document> extractMarkdown(byte[] mdBytes, String filename) {
        try {
            String content = new String(mdBytes, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                log.warn("[MD 추출] {} — 내용이 비어 있습니다.", filename);
                return List.of();
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("type", "markdown");
            meta.put("upload_source", "client");

            log.info("[MD 추출] {} — {}자", filename, content.length());
            return List.of(new Document(content, meta));

        } catch (Exception e) {
            log.error("[MD 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("마크다운 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 클라이언트 Ollama에 문서 요약을 요청합니다 (MCP Sampling).
     * Sampling이 지원되지 않거나 실패하면 빈 요약을 반환합니다.
     */
    private Mono<String> requestSummaryViaSampling(McpAsyncRequestContext ctx,
                                                    List<Document> documents, String filename) {
        // 첫 2개 페이지(또는 전체)의 텍스트를 추려 요약 요청
        StringBuilder excerpt = new StringBuilder();
        int pageLimit = Math.min(documents.size(), 2);
        for (int i = 0; i < pageLimit; i++) {
            String text = documents.get(i).getText();
            if (text != null) {
                int end = Math.min(text.length(), 1500);
                excerpt.append(text, 0, end);
                if (i < pageLimit - 1) excerpt.append("\n\n---\n\n");
            }
        }

        if (excerpt.isEmpty()) {
            return Mono.just("(내용 없음)");
        }

        String prompt = String.format(
                "다음은 '%s' 파일의 내용입니다. 핵심 주제와 내용을 한국어로 2~3문장으로 요약해 주세요.\n\n%s",
                filename, excerpt);

        return ctx.sampleEnabled()
                .flatMap(enabled -> {
                    if (!enabled) {
                        log.info("[Sampling] 클라이언트 Sampling 미지원 — 요약 생략");
                        return Mono.just("(Sampling 미지원)");
                    }
                    return ctx.sample(spec -> spec.message(prompt).maxTokens(300))
                            .map(result -> {
                                if (result.content() instanceof McpSchema.TextContent tc) {
                                    return tc.text() != null ? tc.text() : "(빈 응답)";
                                }
                                return "(비텍스트 응답)";
                            })
                            .onErrorResume(e -> {
                                log.warn("[Sampling] 요약 요청 실패 — {}", e.getMessage());
                                return Mono.just("(요약 실패)");
                            });
                });
    }

    /**
     * 원본 문서(rawDocuments) 기준으로 메타데이터를 저장합니다.
     *
     * loadDocumentsAsync()와 동일한 chunkIndex 계산 방식을 사용합니다.
     *   PDF:       page_number(1-based) → 0-based  (예: 페이지 1→ chunkIndex 0)
     *   Markdown:  page_number 없음    → 0
     *
     * 같은 파일을 두 인덱싱 경로(REST reindex / MCP uploadAndIndexDocument)로 처리해도
     * document_metadata 테이블의 chunkIndex 기준이 일치하여 변경 감지가 올바르게 동작합니다.
     */
    private void saveMetadata(String filename, List<Document> rawDocuments, String summary) {
        for (Document doc : rawDocuments) {
            String content = doc.getText();
            if (content == null || content.isBlank()) continue;

            // loadDocumentsAsync의 getChunkIndex()와 동일 로직
            Object pageNumber = doc.getMetadata().get("page_number");
            int chunkIndex = (pageNumber instanceof Integer p) ? p - 1 : 0;

            String hash = DocumentHashUtil.calculateHash(content);

            try {
                Optional<DocumentMetadata> existing =
                        metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);

                DocumentMetadata meta;
                if (existing.isPresent()) {
                    meta = existing.get();
                    meta.setContentHash(hash);
                    meta.setIndexedAt(LocalDateTime.now());
                } else {
                    meta = new DocumentMetadata(null, filename, chunkIndex, hash, LocalDateTime.now());
                }

                metadataRepository.save(meta);
            } catch (Exception e) {
                log.error("[메타데이터 저장] {} [{}] 실패: {}", filename, chunkIndex, e.getMessage());
            }
        }

        log.info("[메타데이터 저장] {} — {}개 페이지/문서, 요약: {}자",
                filename, rawDocuments.size(), summary.length());
    }
}
