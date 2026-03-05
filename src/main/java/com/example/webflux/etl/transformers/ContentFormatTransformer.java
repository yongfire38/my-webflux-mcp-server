package com.example.webflux.etl.transformers;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ContentFormatTransformer implements DocumentTransformer {

    @Value("${app.document.normalization.enabled:false}")
    private boolean normalizationEnabled;

    @Value("${app.document.normalization.remove-html-tags:true}")
    private boolean removeHtmlTags;

    @Value("${app.document.normalization.normalize-whitespace:true}")
    private boolean normalizeWhitespace;

    @Value("${app.document.normalization.normalize-newlines:true}")
    private boolean normalizeNewlines;

    @Value("${app.document.normalization.remove-code-blocks:false}")
    private boolean removeCodeBlocks;

    @Value("${app.document.normalization.clean-special-chars:true}")
    private boolean cleanSpecialChars;

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern SPECIAL_CHARS_PATTERN = Pattern.compile(
        "[^\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F\\uA960-\\uA97F\\uD7B0-\\uD7FF" +
        "a-zA-Z0-9\\s\\n\\t\\-_.,()\\[\\]{}\"':;!?@#$%&*+=|\\\\/<>]");

    @Override
    public List<Document> apply(List<Document> documents) {
        if (!normalizationEnabled) {
            log.info("문서 정규화가 비활성화되어 있습니다. 원본 문서를 그대로 반환합니다.");
            return documents;
        }

        log.info("문서 형식 변환 시작: {}개 문서", documents.size());

        List<Document> normalizedDocuments = documents.stream()
                .map(this::normalizeDocument)
                .toList();

        log.info("문서 형식 변환 완료: {}개 문서", normalizedDocuments.size());
        return normalizedDocuments;
    }

    private Document normalizeDocument(Document document) {
        String originalContent = document.getText();
        String normalizedContent = originalContent;

        if (removeHtmlTags) {
            normalizedContent = normalizedContent.replaceAll("<[^>]*>", "");
        }

        if (normalizeWhitespace) {
            normalizedContent = normalizedContent.replaceAll("\\s+", " ");
        }

        if (normalizeNewlines) {
            normalizedContent = normalizedContent.replaceAll("\\n{2,}", "\\n");
        }

        if (removeCodeBlocks) {
            normalizedContent = CODE_BLOCK_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        if (cleanSpecialChars) {
            normalizedContent = SPECIAL_CHARS_PATTERN.matcher(normalizedContent).replaceAll("");
        }

        normalizedContent = normalizedContent.trim();

        if (!originalContent.equals(normalizedContent)) {
            document.getMetadata().put("original_length", originalContent.length());
            document.getMetadata().put("normalized_length", normalizedContent.length());
            return new Document(document.getId(), normalizedContent, document.getMetadata());
        }

        return document;
    }
}
