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

import com.example.webflux.config.McpTransportConfig;
import com.example.webflux.config.SecurityProperties;
import com.example.webflux.etl.transformers.ContentFormatTransformer;
import com.example.webflux.etl.transformers.DocumentChunkTransformer;
import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.util.DocumentHashUtil;
import com.example.webflux.util.DocumentOverwriteForbiddenException;
import com.example.webflux.util.StaleVectorCleaner;
import com.example.webflux.util.PromptInjectionDetectedException;
import com.example.webflux.util.PromptInjectionDetector;

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
    private final StaleVectorCleaner staleVectorCleaner;
    private final SecurityProperties securityProperties;

    private static final int PROGRESS_TOTAL_STEPS = 4;

    // 파이프라인 내 rawDocuments + chunks + summary를 함께 전달하기 위한 내부 레코드
    private record UploadContext(List<Document> rawDocuments, List<Document> chunks, String summary) {}

    /**
     * 클라이언트 로컬 문서를 base64로 수신하여 서버 벡터 DB에 임베딩합니다.
     *
     * 처리 흐름:
     *   0. 타 클라이언트 덮어쓰기 차단 / 해시 변경 감지 (미변경 페이지 스킵)
     *   1. base64 디코딩 → 텍스트 추출 (PDF/MD)   [Progress 1/4]
     *   2. Sampling → 클라이언트 Ollama로 문서 요약 요청   [Progress 2/4]
     *   3. 콘텐츠 정규화 + 청킹   [Progress 3/4]
     *   4. 변경 페이지 stale 벡터 선삭제 + 임베딩 저장 + 메타데이터 저장   [Progress 4/4]
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
        // 이슈 #26: transportContext에서 X-MCP-API-Key 헤더 값을 꺼내 신뢰 클라이언트 검증
        String apiKey = (String) ctx.transportContext().get(McpTransportConfig.TRANSPORT_CTX_API_KEY);
        if (!securityProperties.isValidKey(apiKey)) {
            log.warn("[업로드][{}] MCP API 키 인증 실패 — 업로드 거부", jobId);
            return Mono.just(String.format("[%s] 인증 실패 — 업로드가 거부되었습니다. 유효한 X-MCP-API-Key가 필요합니다.", jobId));
        }

        String clientId = ctx.clientInfo() != null ? ctx.clientInfo().name() : "unknown";
        log.info("[업로드] 요청 수신 — jobId: {}, filename: {}, mimeType: {}, clientId: {}",
                jobId, filename, mimeType, clientId);

        return Mono.fromCallable(() -> {
            // 0단계: 타 클라이언트(또는 REST 경로)가 적재한 문서의 덮어쓰기 차단
            checkOverwriteOwnership(filename, clientId, jobId);

            // 1단계: base64 디코딩 + 텍스트 추출
            byte[] fileBytes = decodeBase64(base64Content, jobId, filename);
            List<Document> rawDocuments = extractDocuments(fileBytes, filename, mimeType, clientId);
            log.info("[업로드][{}] 텍스트 추출 완료 — {}페이지", jobId, rawDocuments.size());

            // 자연어 프롬프트 인젝션 의심 패턴 검사
            checkPromptInjection(rawDocuments, jobId, filename);

            // 이슈 #1: SHA-256 해시 비교로 변경된 페이지만 선별 — 미변경 페이지 재임베딩 생략
            List<Document> changedDocuments = filterChangedDocuments(rawDocuments, filename);
            log.info("[업로드][{}] 변경 감지 — 전체: {}페이지, 변경: {}페이지",
                    jobId, rawDocuments.size(), changedDocuments.size());
            return changedDocuments;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(changedDocuments -> {
            if (changedDocuments.isEmpty()) {
                log.info("[업로드][{}] 변경된 페이지 없음 — 임베딩 생략", jobId);
                return Mono.just(String.format(
                        "[%s] %s — 변경된 내용이 없어 임베딩을 생략합니다.", jobId, filename));
            }
            return runPipeline(ctx, changedDocuments, filename, clientId, jobId);
        })
        .doOnError(e -> log.error("[업로드][{}] 처리 중 오류 발생 — {}", jobId, e.getMessage(), e))
        .onErrorResume(PromptInjectionDetectedException.class,
                e -> Mono.just(String.format("[%s] %s 업로드가 거부되었습니다 — %s", jobId, filename, e.getMessage())))
        .onErrorResume(DocumentOverwriteForbiddenException.class,
                e -> Mono.just(String.format("[%s] %s 업로드가 거부되었습니다 — %s", jobId, filename, e.getMessage())))
        .onErrorReturn(String.format("[%s] %s 처리 중 오류가 발생했습니다.", jobId, filename));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 변경된 문서에 대해 Progress 1~4 파이프라인을 실행합니다.
     * filterChangedDocuments()가 비어 있지 않음을 보장한 후 호출됩니다.
     */
    private Mono<String> runPipeline(McpAsyncRequestContext ctx, List<Document> changedDocuments,
            String filename, String clientId, String jobId) {

        return ctx.progress(p -> p.progress(1).total(PROGRESS_TOTAL_STEPS)
                        .message("텍스트 추출 완료 (" + changedDocuments.size() + "페이지)"))
                .thenReturn(changedDocuments)
                .flatMap(docs ->
                    // 2단계: Sampling — 클라이언트 Ollama에 문서 요약 요청
                    requestSummaryViaSampling(ctx, docs, filename)
                        .flatMap(summary -> {
                            log.info("[업로드][{}] 요약 완료 — {}자", jobId, summary.length());
                            return ctx.progress(p -> p.progress(2).total(PROGRESS_TOTAL_STEPS)
                                    .message("요약 완료"))
                                      .thenReturn(Map.entry(docs, summary));
                        })
                )
                .flatMap(entry -> Mono.fromCallable(() -> {
                    // 3단계: 정규화 + 청킹
                    List<Document> normalized = contentFormatTransformer.apply(entry.getKey());
                    List<Document> chunks = documentChunkTransformer.apply(normalized);
                    log.info("[업로드][{}] 청킹 완료 — {}개 청크", jobId, chunks.size());
                    return new UploadContext(entry.getKey(), chunks, entry.getValue());
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ctx3 ->
                    ctx.progress(p -> p.progress(3).total(PROGRESS_TOTAL_STEPS)
                            .message("청킹 완료 (" + ctx3.chunks().size() + "개 청크)"))
                       .thenReturn(ctx3)
                )
                .flatMap(ctx3 -> Mono.fromCallable(() -> {
                    // 4단계: 변경 페이지만 stale 벡터 선삭제 후 새 청크 임베딩
                    // 이슈 #1: 전체 파일 삭제(deleteBySource) 대신 변경된 페이지만 삭제
                    for (Document doc : ctx3.rawDocuments()) {
                        Object pageNum = doc.getMetadata().get("page_number");
                        if (pageNum instanceof Integer p) {
                            staleVectorCleaner.deleteBySourceAndPage(filename, p);
                        } else {
                            staleVectorCleaner.deleteBySource(filename);
                        }
                    }
                    pgVectorStore.add(ctx3.chunks());
                    saveMetadata(filename, ctx3.rawDocuments(), ctx3.summary(), clientId);
                    log.info("[업로드][{}] 임베딩 완료 — {}개 청크 저장", jobId, ctx3.chunks().size());
                    return ctx3;
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ctx3 ->
                    ctx.progress(p -> p.progress(4).total(PROGRESS_TOTAL_STEPS)
                            .message("임베딩 완료 (" + ctx3.chunks().size() + "개 청크 저장)"))
                       .thenReturn(buildReturnMessage(jobId, filename, ctx3.chunks().size(), ctx3.summary()))
                );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 이슈 #1: SHA-256 해시 비교로 변경된 문서(페이지)만 반환합니다.
     * 기존 document_metadata와 해시가 동일한 페이지는 결과에서 제외합니다.
     * 신규 파일(메타데이터 없음) 또는 해시 조회 실패 시 변경으로 간주합니다.
     */
    private List<Document> filterChangedDocuments(List<Document> documents, String filename) {
        List<Document> changed = new ArrayList<>();
        for (Document doc : documents) {
            String content = doc.getText();
            if (content == null || content.isBlank()) continue;
            Object pageNumber = doc.getMetadata().get("page_number");
            int chunkIndex = (pageNumber instanceof Integer p) ? p - 1 : 0;
            String newHash = DocumentHashUtil.calculateHash(content);
            try {
                Optional<DocumentMetadata> existing =
                        metadataRepository.findByFilenameAndChunkIndex(filename, chunkIndex);
                if (existing.isPresent() && newHash.equals(existing.get().getContentHash())) {
                    log.debug("[업로드] {} [{}] — 변경 없음", filename, chunkIndex);
                } else {
                    changed.add(doc);
                }
            } catch (Exception e) {
                log.warn("[업로드] {} [{}] 해시 조회 실패 — 변경으로 간주: {}", filename, chunkIndex, e.getMessage());
                changed.add(doc);
            }
        }
        return changed;
    }

    /**
     * 이슈 #2: 임베딩 완료 반환 메시지에 요약을 포함합니다.
     * Sampling 미지원/실패 등 오류 문자열("("로 시작)은 제외합니다.
     */
    private String buildReturnMessage(String jobId, String filename, int chunkCount, String summary) {
        String base = String.format("[%s] %s 임베딩 완료 — %d개 청크가 RAG 지식 베이스에 추가되었습니다.",
                jobId, filename, chunkCount);
        if (summary != null && !summary.isBlank() && !summary.startsWith("(")) {
            return base + "\n\n[문서 요약]\n" + summary;
        }
        return base;
    }

    /**
     * 동일 파일명으로 이미 적재된 문서가 있고, 출처(sourceClient)가 다르면 덮어쓰기를 거부합니다.
     * 신규 파일(기존 메타데이터 없음)은 제약 없이 통과합니다.
     */
    private void checkOverwriteOwnership(String filename, String clientId, String jobId) {
        List<DocumentMetadata> existing = metadataRepository.findByFilename(filename);
        for (DocumentMetadata meta : existing) {
            String existingOwner = meta.getSourceClient();
            if (existingOwner == null || !existingOwner.equals(clientId)) {
                log.warn("[업로드][{}] 타 클라이언트 문서 덮어쓰기 시도 차단 — {} (기존 출처: {}, 요청: {})",
                        jobId, filename, existingOwner, clientId);
                throw new DocumentOverwriteForbiddenException(
                        "이미 다른 클라이언트가 적재한 문서입니다. 덮어쓸 수 없습니다: " + filename);
            }
        }
    }

    private void checkPromptInjection(List<Document> rawDocuments, String jobId, String filename) {
        for (Document doc : rawDocuments) {
            Optional<String> matched = PromptInjectionDetector.detect(doc.getText());
            if (matched.isPresent()) {
                log.warn("[업로드][{}] 프롬프트 인젝션 의심 패턴 탐지 — {} (패턴: {})", jobId, filename, matched.get());
                throw new PromptInjectionDetectedException(
                        "문서 내용에서 지시 탈취(prompt injection)로 의심되는 패턴이 발견되어 업로드를 거부했습니다.");
            }
        }
    }

    private byte[] decodeBase64(String base64Content, String jobId, String filename) {
        try {
            return Base64.getDecoder().decode(base64Content.trim());
        } catch (IllegalArgumentException e) {
            log.error("[업로드][{}] Base64 디코딩 실패 — {}", jobId, filename);
            throw new IllegalArgumentException("Base64 디코딩 실패: " + e.getMessage(), e);
        }
    }

    private List<Document> extractDocuments(byte[] fileBytes, String filename, String mimeType, String clientId) {
        boolean isPdf = "application/pdf".equalsIgnoreCase(mimeType)
                || filename.toLowerCase().endsWith(".pdf");
        return isPdf ? extractPdf(fileBytes, filename, clientId) : extractMarkdown(fileBytes, filename, clientId);
    }

    private List<Document> extractPdf(byte[] pdfBytes, String filename, String clientId) {
        try {
            ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() { return filename; }
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
                meta.put("upload_source", clientId);
                result.add(new Document(content, meta));
            }

            log.info("[PDF 추출] {} — {}페이지", filename, result.size());
            return result;

        } catch (Exception e) {
            log.error("[PDF 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("PDF 추출 실패: " + e.getMessage(), e);
        }
    }

    private List<Document> extractMarkdown(byte[] mdBytes, String filename, String clientId) {
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
            meta.put("upload_source", clientId);

            log.info("[MD 추출] {} — {}자", filename, content.length());
            return List.of(new Document(content, meta));

        } catch (Exception e) {
            log.error("[MD 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("마크다운 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 클라이언트 Ollama에 문서 요약을 요청합니다 (MCP Sampling).
     * Sampling이 지원되지 않거나 실패하면 빈 요약 문자열을 반환합니다.
     */
    private Mono<String> requestSummaryViaSampling(McpAsyncRequestContext ctx,
                                                    List<Document> documents, String filename) {
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
     * 이슈 #2: summary를 document_metadata 테이블에 함께 저장합니다.
     */
    private void saveMetadata(String filename, List<Document> rawDocuments, String summary, String clientId) {
        for (Document doc : rawDocuments) {
            String content = doc.getText();
            if (content == null || content.isBlank()) continue;

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
                    meta.setSourceClient(clientId);
                    meta.setSummary(summary);
                } else {
                    meta = new DocumentMetadata(null, filename, chunkIndex, hash,
                            LocalDateTime.now(), clientId, summary);
                }

                metadataRepository.save(meta);
            } catch (Exception e) {
                log.error("[메타데이터 저장] {} [{}] 실패: {}", filename, chunkIndex, e.getMessage());
            }
        }

        log.info("[메타데이터 저장] {} — {}개 페이지/문서, 요약: {}자, clientId: {}",
                filename, rawDocuments.size(), summary != null ? summary.length() : 0, clientId);
    }
}
