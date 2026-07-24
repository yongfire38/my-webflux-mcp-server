package com.example.webflux.service;

import java.util.concurrent.CompletableFuture;

import com.example.webflux.dto.DocumentStatusResponse;

public interface DocumentManagementService {

    CompletableFuture<Integer> loadDocumentsAsync();

    boolean isProcessing();

    DocumentStatusResponse getStatusResponse();

    String reindexDocuments();

}
