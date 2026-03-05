package com.example.webflux.etl.readers;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PdfDocumentReader implements DocumentReader {

    @Value("${app.document.pdf-path:#{null}}")
    private String pdfDocumentPath;

    @Value("${app.document.pdf.page-top-margin:0}")
    private int pageTopMargin;

    @Value("${app.document.pdf.pages-per-document:1}")
    private int pagesPerDocument;

    @Override
    public List<Document> get() {
        if (pdfDocumentPath == null || pdfDocumentPath.trim().isEmpty()) {
            log.info("PDF 문서 경로가 설정되지 않았습니다. PDF 문서 읽기를 건너뜁니다.");
            return List.of();
        }

        log.info("PDF 문서 읽기 시작 - 경로: {}", pdfDocumentPath);

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(pdfDocumentPath);

            if (resources.length == 0) {
                log.warn("PDF 파일을 찾을 수 없습니다: {}", pdfDocumentPath);
                return List.of();
            }

            log.info("{}개의 PDF 파일을 찾았습니다.", resources.length);

            List<Document> allDocuments = new ArrayList<>();

            for (Resource resource : resources) {
                log.info("PDF 파일 처리 중: {}", resource.getFilename());

                try {
                    PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                        resource,
                        PdfDocumentReaderConfig.builder()
                            .withPageTopMargin(pageTopMargin)
                            .withPagesPerDocument(pagesPerDocument)
                            .build()
                    );

                    List<Document> documents = pdfReader.read();
                    log.info("PDF 파일 '{}'에서 {}개의 문서를 읽었습니다.",
                        resource.getFilename(), documents.size());

                    List<Document> documentsWithCustomIds = createDocumentsWithCustomIds(
                        documents, resource.getFilename());

                    allDocuments.addAll(documentsWithCustomIds);

                } catch (Exception e) {
                    log.error("PDF 파일 '{}' 처리 중 오류 발생: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("총 {}개의 PDF 문서를 읽었습니다.", allDocuments.size());
            return allDocuments;

        } catch (Exception e) {
            log.error("PDF 문서 읽기 중 오류 발생", e);
            return List.of();
        }
    }

    private List<Document> createDocumentsWithCustomIds(List<Document> documents, String filename) {
        List<Document> documentsWithCustomIds = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            String content = document.getText();

            String baseFilename = filename.replaceAll("\\.pdf$", "");
            String safeFilename = baseFilename.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", "-");
            String customId = String.format("pdf-%s_%d", safeFilename, i + 1);

            document.getMetadata().put("original_id", document.getId());
            document.getMetadata().put("page_number", i + 1);
            document.getMetadata().put("file_name", filename);
            document.getMetadata().put("source", filename);
            document.getMetadata().put("type", "pdf");
            document.getMetadata().put("content_length", content != null ? content.length() : 0);

            documentsWithCustomIds.add(new Document(customId, content, document.getMetadata()));
        }

        return documentsWithCustomIds;
    }
}
