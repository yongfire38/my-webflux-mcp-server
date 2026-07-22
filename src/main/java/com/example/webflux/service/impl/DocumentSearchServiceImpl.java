package com.example.webflux.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.webflux.model.DocumentMetadata;
import com.example.webflux.repository.DocumentMetadataRepository;
import com.example.webflux.service.DocumentSearchService;
import com.example.webflux.util.RrfFusion;

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
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.rag.similarity-threshold:0.30}")
    private double similarityThreshold;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.max-content-length:2000}")
    private int maxContentLength;

    @Value("${app.rag.hybrid.enabled:false}")
    private boolean hybridEnabled;

    @Value("${app.rag.hybrid.weight.dense:1.0}")
    private double hybridDenseWeight;

    @Value("${app.rag.hybrid.weight.lexical:1.0}")
    private double hybridLexicalWeight;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable;

    // pg_trgm.word_similarity_threshold 기본값 0.6은 너무 엄격 — 트랜잭션 스코프로 0.30으로 설정
    @Value("${app.rag.hybrid.lexical.word-similarity-threshold:0.30}")
    private double wordSimilarityThreshold;

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
        log.info("문서 검색 ({}) - 질의: {}", hybridEnabled ? "하이브리드" : "dense", query);

        return Mono.fromCallable(() -> {
            List<Document> results = hybridEnabled ? hybridSearch(query) : denseSearch(query);

            if (results == null || results.isEmpty()) {
                log.info("검색 결과 없음 - 질의: {}", query);
                return "관련 문서를 찾을 수 없습니다. "
                        + "다른 키워드로 재시도하거나 describeKnowledgeBase로 인덱싱 현황을 확인하세요.";
            }

            log.info("검색 결과 {}건 - 질의: {}", results.size(), query);
            if (log.isDebugEnabled()) {
                for (int i = 0; i < results.size(); i++) {
                    Document d = results.get(i);
                    log.debug("  [{}] 출처: {} (유사도: {})",
                            i + 1,
                            d.getMetadata().getOrDefault("source", "unknown"),
                            d.getScore() != null ? String.format("%.3f", d.getScore()) : "-");
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("검색 결과 %d건 (모드: %s)\n\n",
                    results.size(), hybridEnabled ? "하이브리드(dense+lexical RRF)" : "dense"));

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
    @Override
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

    // ── private helpers ──────────────────────────────────────────────────────

    private List<Document> denseSearch(String query) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );
    }

    /**
     * dense + lexical 채널을 RRF로 융합한 하이브리드 검색.
     *
     * <p>lexical 채널 실패 시 dense 결과만 반환한다(graceful degrade). 실패 원인에 따른 영향:
     * <ul>
     *   <li>pg_trgm 확장 미설치: {@code %} 연산자 없음 → SQL 예외 → dense 단독 폴백</li>
     *   <li>GIN 인덱스 없음: 순차 스캔으로 동작 — 기능 정상, 성능 저하</li>
     * </ul>
     * </p>
     */
    private List<Document> hybridSearch(String query) {
        // 1) dense 채널
        List<Document> denseResults = denseSearch(query);

        // 2) lexical 채널 — 실패 시 dense 단독 폴백
        List<String> lexicalRanking;
        try {
            lexicalRanking = lexicalSearch(query);
        } catch (Exception e) {
            log.warn("lexical 검색 실패 - dense 결과만 사용합니다. 원인: {}", e.getMessage());
            return denseResults;
        }

        // 3) dense 순위 및 키→Document 매핑 구성
        Map<String, Document> byKey = new LinkedHashMap<>();
        List<String> denseRanking = new ArrayList<>(denseResults.size());
        for (Document doc : denseResults) {
            String key = doc.getId();
            denseRanking.add(key);
            byKey.putIfAbsent(key, doc);
        }

        // 4) RRF 융합
        List<String> fusedKeys = RrfFusion.fuse(
                denseRanking, lexicalRanking,
                hybridDenseWeight, hybridLexicalWeight,
                RrfFusion.DEFAULT_K, topK);

        // lexical 단독 문서(dense에 없는 키)는 Document 본문이 없으므로 제외하고,
        // 융합 점수로 재정렬된 dense 문서만 반환한다. lexical 신호는 순위 가중치로 반영된다.
        List<Document> result = new ArrayList<>(fusedKeys.size());
        for (String key : fusedKeys) {
            Document doc = byKey.get(key);
            if (doc != null) {
                result.add(doc);
            }
        }

        log.debug("하이브리드 검색 완료 - dense: {}, lexical: {}, 융합: {}",
                denseResults.size(), lexicalRanking.size(), result.size());
        return result;
    }

    /**
     * pg_trgm {@code %>} (word_similarity) 연산자를 이용한 lexical 검색.
     *
     * <p>{@code similarity(%)} 연산자는 질문·문서 trigram 합집합으로 나눠 4000자 청크에서
     * 유사도가 0.006~0.058로 폭락한다. {@code %>>}(word_similarity)는 문서 내에서 질문과
     * 가장 잘 맞는 구간만 비교하므로 문서 길이에 강건하다 (측정 recall@3 = 0.80, 임계값 0.30).</p>
     *
     * <p>{@code pg_trgm.word_similarity_threshold} 기본값(0.6)은 너무 엄격하므로
     * {@code set_config(is_local=true)}로 트랜잭션 범위에서만 0.30으로 조정한다.
     * 트랜잭션 종료 시 자동 복원되어 커넥션 풀 누수가 없다.</p>
     *
     * <p>연산자 방향: {@code content %> ?} = {@code word_similarity(query, content) >= threshold}
     * ORDER BY: {@code word_similarity(?, content)} — 질문이 앞, 문서가 뒤</p>
     *
     * @return 융합 키 역할을 하는 문서 UUID 문자열 리스트 (유사도 내림차순)
     */
    private List<String> lexicalSearch(String query) {
        List<String> ids = new TransactionTemplate(transactionManager).execute(status -> {
            // is_local=true: 이 트랜잭션 안에서만 임계값 적용, 커밋 시 자동 복원
            jdbcTemplate.queryForObject(
                    "SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)",
                    String.class,
                    Double.toString(wordSimilarityThreshold));
            String sql = "SELECT id::text FROM " + vectorStoreTable
                    + " WHERE content %> ? ORDER BY word_similarity(?, content) DESC LIMIT ?";
            return jdbcTemplate.queryForList(sql, String.class, query, query, topK);
        });
        return ids != null ? ids : List.of();
    }
}
