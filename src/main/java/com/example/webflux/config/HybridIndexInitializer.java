package com.example.webflux.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 하이브리드 lexical 검색용 pg_trgm GIN 인덱스 초기화.
 *
 * <p>{@code app.rag.hybrid.enabled=true} 일 때만 활성화된다. 애플리케이션 기동 완료
 * 시점에 {@code vector_store.content} 컬럼에 GIN trigram 인덱스를 멱등(IF NOT EXISTS)
 * 으로 생성한다.</p>
 *
 * <p>{@code pg_trgm} 확장 자체는 컨테이너 초기화(init-scripts/01-init.sql)에서 설치한다.
 * Docker 없이 기존 PostgreSQL을 사용하는 경우 superuser로
 * {@code CREATE EXTENSION pg_trgm}을 수동 실행해야 한다.</p>
 *
 * <p>장애 케이스별 영향:
 * <ul>
 *   <li>pg_trgm 확장 미설치: {@code %} 연산자 자체가 없으므로 lexical 검색 호출 시 SQL 예외
 *       발생 → {@code DocumentSearchServiceImpl.hybridSearch()} 에서 catch 후 dense 단독으로
 *       폴백. 기능은 보존되나 lexical 신호 없음.</li>
 *   <li>확장은 있고 인덱스만 없음: lexical 검색은 정상 동작하나 content 컬럼 순차 스캔으로
 *       성능 저하. 데이터 양이 적을 때는 실질적 문제 없음.</li>
 * </ul>
 * 두 케이스를 구별해 로그에 정확한 영향을 기록한다.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.rag.hybrid", name = "enabled", havingValue = "true")
public class HybridIndexInitializer {

    private static final String EXTENSION_PROBE_SQL =
            "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'";

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public HybridIndexInitializer(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initTrigramIndex() {
        // 1단계: pg_trgm 확장 설치 여부 확인
        boolean extensionInstalled;
        try {
            Integer count = jdbcTemplate.queryForObject(EXTENSION_PROBE_SQL, Integer.class);
            extensionInstalled = count != null && count > 0;
        } catch (Exception e) {
            log.warn("pg_trgm 확장 설치 여부를 확인할 수 없습니다. 원인: {}", e.getMessage());
            extensionInstalled = false;
        }

        if (!extensionInstalled) {
            // lexical 검색 쿼리 실행 시 % 연산자 미존재로 SQL 예외 발생
            // → DocumentSearchServiceImpl.hybridSearch()가 catch 후 dense 단독 폴백
            log.warn("pg_trgm 확장이 설치되어 있지 않습니다. "
                    + "lexical 검색 시 SQL 예외가 발생하며 dense 검색 단독으로 폴백됩니다. "
                    + "컨테이너를 삭제 후 재생성하거나 superuser로 "
                    + "'CREATE EXTENSION pg_trgm'을 수동 실행하세요.");
            return;
        }

        // 2단계: GIN trigram 인덱스 생성 (pg_trgm 확장이 있으므로 생성 가능)
        String indexName = "idx_" + tableName + "_content_trgm";
        String sql = "CREATE INDEX IF NOT EXISTS " + indexName
                + " ON " + tableName + " USING gin (content gin_trgm_ops)";
        try {
            jdbcTemplate.execute(sql);
            log.info("하이브리드 lexical GIN 인덱스 준비 완료 - table: {}, index: {}", tableName, indexName);
        } catch (Exception e) {
            // 인덱스가 없으면 lexical 검색은 순차 스캔으로 동작 — 기능은 유지, 성능 저하
            log.warn("trigram GIN 인덱스 생성 실패 - lexical 검색은 순차 스캔으로 동작합니다 "
                    + "(기능 영향 없음, 데이터 양에 따라 성능 저하 가능). 원인: {}", e.getMessage());
        }
    }
}
