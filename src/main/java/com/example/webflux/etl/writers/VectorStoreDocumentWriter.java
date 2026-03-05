package com.example.webflux.etl.writers;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class VectorStoreDocumentWriter implements DocumentWriter {

    private final PgVectorStore pgVectorStore;

    @Override
    public void accept(List<Document> documents) {
        log.info("벡터 저장소에 {}개 문서 저장 시작", documents.size());

        if (documents.isEmpty()) {
            log.warn("저장할 문서가 없습니다.");
            return;
        }

        try {
            pgVectorStore.add(documents);
            log.info("벡터 저장소에 {}개 문서 저장 완료", documents.size());
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }
}
