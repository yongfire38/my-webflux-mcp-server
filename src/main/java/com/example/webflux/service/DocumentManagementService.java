package com.example.webflux.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.codec.multipart.FilePart;

import com.example.webflux.dto.DocumentStatusResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DocumentManagementService {

    CompletableFuture<Integer> loadDocumentsAsync();

    boolean isProcessing();

    DocumentStatusResponse getStatusResponse();

    String reindexDocuments();

    Mono<Map<String, Object>> uploadMarkdownFiles(Flux<FilePart> files);

}
