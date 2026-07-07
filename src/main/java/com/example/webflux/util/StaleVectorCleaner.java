package com.example.webflux.util;

import java.util.List;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * vector_store 테이블에서 stale(구버전) 벡터를 삭제하는 유틸리티.
 *
 * <p>PgVectorStore.add()는 INSERT만 수행하므로, 파일 내용이 바뀐 후 재인덱싱 시
 * 기존 벡터를 먼저 제거하지 않으면 old/new 청크가 함께 검색된다.
 * add() 직전에 이 클래스의 메서드를 호출하여 stale 벡터를 선삭제한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleVectorCleaner {

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorStore pgVectorStore;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable;

    /**
     * 파일명 기준으로 해당 파일의 벡터 전체를 삭제한다.
     * 마크다운 파일 갱신, MCP 업로드 경로(파일 전체 재전송) 시 사용.
     */
    public int deleteBySource(String filename) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable + " WHERE metadata->>'source' = ?",
                String.class, filename);
        return deleteAndLog(ids, filename, null);
    }

    /**
     * PDF 페이지 단위로 해당 페이지의 벡터만 삭제한다.
     * REST reindex 경로에서 변경된 PDF 페이지만 재처리할 때 사용.
     */
    public int deleteBySourceAndPage(String filename, int pageNumber) {
        List<String> ids = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable
                        + " WHERE metadata->>'source' = ? AND (metadata->>'page_number')::int = ?",
                String.class, filename, pageNumber);
        return deleteAndLog(ids, filename, pageNumber);
    }

    private int deleteAndLog(List<String> ids, String filename, Integer pageNumber) {
        if (ids.isEmpty()) {
            return 0;
        }
        pgVectorStore.delete(ids);
        if (pageNumber != null) {
            log.info("[stale 벡터 삭제] {} 페이지 {} — {}개 삭제", filename, pageNumber, ids.size());
        } else {
            log.info("[stale 벡터 삭제] {} — {}개 삭제", filename, ids.size());
        }
        return ids.size();
    }
}
