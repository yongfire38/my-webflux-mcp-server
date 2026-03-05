package com.example.webflux.etl.transformers;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DocumentChunkTransformer implements DocumentTransformer {

    @Value("${app.document.chunk-size:4000}")
    private int chunkSize;

    @Value("${app.document.min-chunk-size-chars:350}")
    private int minChunkSizeChars;

    @Value("${app.document.max-num-chunks:500}")
    private int maxNumChunks;

    @Value("${app.document.min-chunk-length-to-embed:50}")
    private int minChunkLengthToEmbed;

    @Override
    public List<Document> apply(List<Document> documents) {
        log.info("문서 청크 분할 시작: {}개 문서", documents.size());
        log.info("TokenTextSplitter 설정 - chunkSize: {}, minChunkSizeChars: {}, minChunkLengthToEmbed: {}, maxNumChunks: {}",
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks);

        TokenTextSplitter textSplitter = new TokenTextSplitter(
            chunkSize,
            minChunkSizeChars,
            minChunkLengthToEmbed,
            maxNumChunks,
            true
        );

        List<Document> splitDocs = textSplitter.apply(documents);
        log.info("문서 청크 분할 완료: {}개 청크 생성", splitDocs.size());

        return splitDocs;
    }
}
