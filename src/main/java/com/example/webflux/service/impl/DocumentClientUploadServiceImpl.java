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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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
import com.example.webflux.util.DocumentOverwriteForbiddenException;
import com.example.webflux.util.StaleVectorCleaner;
import com.example.webflux.util.PromptInjectionDetectedException;
import com.example.webflux.util.PromptInjectionDetector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentClientUploadServiceImpl extends EgovAbstractServiceImpl {

    private final ContentFormatTransformer contentFormatTransformer;
    private final DocumentChunkTransformer documentChunkTransformer;
    private final PgVectorStore pgVectorStore;
    private final DocumentMetadataRepository metadataRepository;
    private final StaleVectorCleaner staleVectorCleaner;
    private final ChatClient chatClient;

    /**
     * 클라이언트 로컬 문서(PDF/MD)를 base64로 수신하여 서버 RAG 벡터 DB에 임베딩합니다.
     *
     * @param ctx          MCP 컨텍스트 (ownerUserId 식별에 사용)
     * @param jobId        작업 추적용 고유 ID
     * @param filename     파일명 (예: document.pdf, guide.md)
     * @param base64Content 파일 내용을 Base64 인코딩한 문자열
     * @param mimeType     MIME 타입: application/pdf 또는 text/markdown
     */
    @McpTool(
        name = "uploadAndIndexDocument",
        description = "클라이언트 로컬 문서(PDF/MD)를 base64로 수신하여 서버 RAG 벡터 DB에 임베딩합니다. "
                + "지원 형식: PDF (.pdf), 마크다운 (.md). jobId는 작업 추적에 사용됩니다."
    )
    public Mono<String> uploadAndIndexDocument(
            McpAsyncRequestContext ctx,
            @McpToolParam(description = "작업 추적용 고유 ID (UUID 권장)") String jobId,
            @McpToolParam(description = "파일명 (예: document.pdf, guide.md)") String filename,
            @McpToolParam(description = "파일 내용을 Base64 인코딩한 문자열") String base64Content,
            @McpToolParam(description = "MIME 타입: application/pdf 또는 text/markdown") String mimeType
    ) {
        return ReactiveSecurityContextHolder.getContext()
            .map(secCtx -> {
                var auth = secCtx.getAuthentication();
                return auth != null && auth.getPrincipal() instanceof String s ? s : "unknown";
            })
            .defaultIfEmpty("unknown")
            .flatMap(ownerUserId -> {
        log.info("[업로드] 요청 수신 — jobId: {}, filename: {}, ownerUserId: {}", jobId, filename, ownerUserId);

        return Mono.fromCallable(() -> {
            checkOverwriteOwnership(filename, ownerUserId, jobId);

            byte[] fileBytes = decodeBase64(base64Content, jobId, filename);
            List<Document> rawDocuments = extractDocuments(fileBytes, filename, mimeType, ownerUserId);

            if (rawDocuments.isEmpty()) {
                return String.format("[%s] %s — 추출된 내용이 없습니다.", jobId, filename);
            }

            List<Document> changedDocuments = filterChangedDocuments(rawDocuments, filename);
            if (changedDocuments.isEmpty()) {
                log.info("[업로드][{}] {} — 변경 없음, 재임베딩 생략", jobId, filename);
                return String.format("[%s] %s — 변경된 내용이 없습니다. 기존 임베딩을 유지합니다.", jobId, filename);
            }
            log.info("[업로드][{}] {} — {}개 중 {}개 페이지 변경", jobId, filename, rawDocuments.size(), changedDocuments.size());

            checkPromptInjection(changedDocuments, jobId, filename);

            String summary = generateSummary(changedDocuments, filename, jobId);

            List<Document> normalized = contentFormatTransformer.apply(changedDocuments);
            List<Document> chunks = documentChunkTransformer.apply(normalized);
            log.info("[업로드][{}] 청킹 완료 — {}개 청크", jobId, chunks.size());

            for (Document doc : changedDocuments) {
                Object pageNum = doc.getMetadata().get("page_number");
                if (pageNum instanceof Integer p) {
                    staleVectorCleaner.deleteBySourceAndPage(filename, p);
                } else {
                    staleVectorCleaner.deleteBySource(filename);
                }
            }
            pgVectorStore.add(chunks);
            saveMetadata(filename, changedDocuments, summary, ownerUserId);

            log.info("[업로드][{}] 임베딩 완료 — {}개 청크 저장", jobId, chunks.size());
            return String.format("[%s] %s 임베딩 완료 — %d개 청크 추가 (%d/%d 페이지 변경).\n\n[문서 요약]\n%s",
                    jobId, filename, chunks.size(), changedDocuments.size(), rawDocuments.size(), summary);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> log.error("[업로드][{}] 처리 중 오류 발생 — {}", jobId, e.getMessage(), e))
        .onErrorResume(PromptInjectionDetectedException.class,
                e -> Mono.just(String.format("[%s] %s 업로드가 거부되었습니다 — %s", jobId, filename, e.getMessage())))
        .onErrorResume(DocumentOverwriteForbiddenException.class,
                e -> Mono.just(String.format("[%s] %s 업로드가 거부되었습니다 — %s", jobId, filename, e.getMessage())))
        .onErrorReturn(String.format("[%s] %s 처리 중 오류가 발생했습니다.", jobId, filename));
        }); // flatMap(ownerUserId -> ...)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void checkOverwriteOwnership(String filename, String ownerUserId, String jobId) {
        List<DocumentMetadata> existing = metadataRepository.findByFilename(filename);
        for (DocumentMetadata meta : existing) {
            String existingOwner = meta.getOwnerUserId();
            if (existingOwner == null || !existingOwner.equals(ownerUserId)) {
                log.warn("[업로드][{}] 타 소유자 문서 덮어쓰기 시도 차단 — {} (기존 소유자: {}, 요청자: {})",
                        jobId, filename, existingOwner, ownerUserId);
                throw new DocumentOverwriteForbiddenException(
                        "이미 다른 소유자가 적재한 문서입니다. 덮어쓸 수 없습니다: " + filename);
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

    private List<Document> extractDocuments(byte[] fileBytes, String filename, String mimeType, String ownerUserId) {
        boolean isPdf = "application/pdf".equalsIgnoreCase(mimeType)
                || filename.toLowerCase().endsWith(".pdf");
        return isPdf
                ? extractPdf(fileBytes, filename, ownerUserId)
                : extractMarkdown(fileBytes, filename, ownerUserId);
    }

    private List<Document> extractPdf(byte[] pdfBytes, String filename, String ownerUserId) {
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
                meta.put("owner_user_id", ownerUserId);
                result.add(new Document(content, meta));
            }

            log.info("[PDF 추출] {} — {}페이지", filename, result.size());
            return result;

        } catch (Exception e) {
            log.error("[PDF 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("PDF 추출 실패: " + e.getMessage(), e);
        }
    }

    private List<Document> extractMarkdown(byte[] mdBytes, String filename, String ownerUserId) {
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
            meta.put("owner_user_id", ownerUserId);

            log.info("[MD 추출] {} — {}자", filename, content.length());
            return List.of(new Document(content, meta));

        } catch (Exception e) {
            log.error("[MD 추출] {} 처리 중 오류: {}", filename, e.getMessage());
            throw new RuntimeException("마크다운 추출 실패: " + e.getMessage(), e);
        }
    }

    private String generateSummary(List<Document> documents, String filename, String jobId) {
        StringBuilder excerpt = new StringBuilder();
        int pageLimit = Math.min(documents.size(), 2);
        for (int i = 0; i < pageLimit; i++) {
            String text = documents.get(i).getText();
            if (text != null) {
                excerpt.append(text, 0, Math.min(text.length(), 1500));
                if (i < pageLimit - 1) excerpt.append("\n\n---\n\n");
            }
        }

        if (excerpt.isEmpty()) return "(내용 없음)";

        String prompt = String.format(
                "다음은 '%s' 파일의 내용입니다. 핵심 주제와 내용을 한국어로 2~3문장으로 요약해 주세요.\n\n%s",
                filename, excerpt);

        try {
            String result = chatClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "doc-summary-" + jobId))
                    .call()
                    .content();
            return result != null ? result : "(빈 응답)";
        } catch (Exception e) {
            log.warn("[요약] Ollama 요약 실패 — {}", e.getMessage());
            return "(요약 실패)";
        }
    }

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

    private void saveMetadata(String filename, List<Document> rawDocuments, String summary, String ownerUserId) {
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
                    meta.setOwnerUserId(ownerUserId);
                } else {
                    meta = new DocumentMetadata(null, filename, chunkIndex, hash, LocalDateTime.now(), ownerUserId);
                }

                metadataRepository.save(meta);
            } catch (Exception e) {
                log.error("[메타데이터 저장] {} [{}] 실패: {}", filename, chunkIndex, e.getMessage());
            }
        }

        log.info("[메타데이터 저장] {} — {}개 페이지/문서, 요약: {}자, ownerUserId: {}",
                filename, rawDocuments.size(), summary.length(), ownerUserId);
    }
}
