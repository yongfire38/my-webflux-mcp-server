package com.example.webflux.etl;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.webflux.etl.writers.VectorStoreDocumentWriter;

import lombok.extern.slf4j.Slf4j;

/**
 * ETL 파이프라인 설정.
 * MarkdownDocumentReader, PdfDocumentReader, ContentFormatTransformer,
 * DocumentChunkTransformer는 @Component로 자동 등록되므로 여기서는
 * VectorStoreDocumentWriter만 수동 빈 등록한다.
 */
@Slf4j
@Configuration
public class ETLPipelineConfig {

    @Bean
    public VectorStoreDocumentWriter vectorStoreWriter(PgVectorStore pgVectorStore) {
        log.info("VectorStoreDocumentWriter 빈 생성");
        return new VectorStoreDocumentWriter(pgVectorStore);
    }
}
