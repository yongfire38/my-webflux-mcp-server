package com.example.webflux.dto;

public record DocumentStatusResponse(
    boolean processing,
    int processedCount,
    int totalCount,
    int changedCount,
    boolean hasDocuments
) {
    public DocumentStatusResponse(boolean processing, int processedCount, int totalCount, int changedCount) {
        this(processing, processedCount, totalCount, changedCount, totalCount > 0);
    }
}
