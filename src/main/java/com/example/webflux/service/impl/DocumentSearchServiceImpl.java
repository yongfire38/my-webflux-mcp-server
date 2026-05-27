package com.example.webflux.service.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.document.Document;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.DocumentSearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentSearchServiceImpl extends EgovAbstractServiceImpl implements DocumentSearchService {

    private final VectorStore vectorStore;
    private final DocumentMetadataRepository documentMetadataRepository;

    @Value("${app.rag.similarity-threshold:0.30}")
    private double similarityThreshold;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.max-content-length:2000}")
    private int maxContentLength;

    /**
     * [RAG 핵심 도구] 벡터 유사도 기반 문서 검색
     *
     * 도구 설명에 "필수", "반드시", 트리거 키워드를 명시하여
     * LLM이 자체 학습 지식 대신 문서 검색을 우선하도록 유도합니다.
     */
    @Override
    @McpTool(name = "searchDocuments",
          description = "【필수 RAG 도구】 지식 베이스에 저장된 모든 문서에서 관련 내용을 벡터 유사도 검색합니다. "
                  + "사용자가 어떤 주제(기술, 제품, 역사, 사용법 등)에 대해 질문하면 반드시 이 도구를 먼저 호출하세요. "
                  + "검색 없이 학습 지식으로만 답변하지 마세요. "
                  + "결과가 없으면 describeKnowledgeBase로 인덱싱 현황을 확인하세요.")
    public Mono<String> searchDocuments(
            @McpToolParam(description = "검색 질의문. 구체적일수록 정확도가 높아집니다.") String query
    ) {
        log.info("문서 검색 - 질의: {}", query);

        return Mono.fromCallable(() -> {
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold)
                            .build()
            );

            if (results == null || results.isEmpty()) {
                log.info("검색 결과 없음 - 질의: {}", query);
                return "관련 문서를 찾을 수 없습니다. "
                        + "다른 키워드로 재시도하거나 describeKnowledgeBase로 인덱싱 현황을 확인하세요.";
            }

            log.info("검색 결과 {}건 - 질의: {}", results.size(), query);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("검색 결과 %d건 (유사도 임계값: %.2f)\n\n", results.size(), similarityThreshold));

            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                String content = doc.getText();
                if (content != null && content.length() > maxContentLength) {
                    content = content.substring(0, maxContentLength) + "...";
                }
                String source = (String) doc.getMetadata().getOrDefault("source", "unknown");
                double score = doc.getScore() != null ? doc.getScore() : 0.0;

                sb.append(String.format("[%d] 출처: %s (유사도: %.3f)\n%s\n\n", i + 1, source, score, content));
            }

            return sb.toString();

        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn("문서 검색 중 오류가 발생했습니다.");
    }

    /**
     * [RAG 사전 확인 도구] 지식 베이스 현황 조회
     *
     * searchDocuments 호출 전에 어떤 파일이 인덱싱되어 있는지 파악하는 용도.
     * "이 주제가 문서에 있나요?" → describeKnowledgeBase → searchDocuments 2단계 패턴 유도.
     */
    @McpTool(name = "describeKnowledgeBase",
          description = "RAG 지식 베이스에 인덱싱된 문서 현황을 조회합니다. "
                  + "searchDocuments 호출 전 어떤 파일이 검색 가능한지 확인하세요. "
                  + "'어떤 문서가 있나요?', '검색 가능한 주제는?' 질문에 사용합니다.")
    public Mono<String> describeKnowledgeBase() {
        log.info("지식 베이스 현황 조회");

        return Mono.fromCallable(() -> {
            long totalChunks = documentMetadataRepository.count();

            if (totalChunks == 0) {
                return "인덱싱된 문서가 없습니다. 먼저 문서를 업로드하고 인덱싱하세요.";
            }

            List<DocumentMetadata> all = documentMetadataRepository.findAll();
            Map<String, Long> fileChunks = all.stream()
                    .collect(Collectors.groupingBy(DocumentMetadata::getFilename, Collectors.counting()));

            StringBuilder sb = new StringBuilder();
            sb.append("=== RAG 지식 베이스 현황 ===\n");
            sb.append(String.format("총 파일 수: %d개 | 총 청크 수: %d개\n\n", fileChunks.size(), totalChunks));
            sb.append("파일 목록 (파일명 - 청크 수):\n");
            fileChunks.forEach((filename, count) ->
                    sb.append(String.format("  - %s (%d 청크)\n", filename, count)));
            sb.append("\nsearchDocuments 도구로 이 파일들의 내용을 검색할 수 있습니다.");

            return sb.toString();

        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorReturn("지식 베이스 조회 중 오류가 발생했습니다.");
    }
}
